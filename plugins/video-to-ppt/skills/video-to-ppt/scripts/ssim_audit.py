"""Post-hash SSIM matrix and first-representative second-stage deduplication."""
import csv
import html
import importlib
import json
from pathlib import Path
import time

import numpy as np
from PIL import Image


def require_ssim():
    try:
        package = importlib.import_module('skimage')
        metrics = importlib.import_module('skimage.metrics')
    except ImportError as error:
        raise ValueError('SSIM audit requires scikit-image and scipy in this Python environment; '
                         'install the declared dependencies or explicitly use --skip-ssim-audit. '
                         'Audit was not silently skipped.') from error
    return metrics.structural_similarity, package.__version__


def prepare_ssim_image(path, width, height):
    with Image.open(path) as image:
        return np.asarray(image.convert('L').resize((width, height),
                          Image.Resampling.LANCZOS), dtype=np.float32)


def ssim_parameters():
    return dict(data_range=255, channel_axis=None, gaussian_weights=True,
                sigma=1.5, win_size=11, use_sample_covariance=False,
                K1=0.01, K2=0.03, full=False, gradient=False)


def classify_ssim(matrix, threshold):
    """Merge only into the first retained input at score >= threshold; never bridge discarded inputs."""
    values = np.asarray(matrix, dtype=np.float64)
    if (isinstance(threshold, bool) or not isinstance(threshold, (int, float))
            or not np.isfinite(threshold) or not 0 <= threshold <= 1):
        raise ValueError('SSIM threshold must be finite and in [0,1]')
    if (values.ndim != 2 or values.shape[0] != values.shape[1]
            or not np.isfinite(values).all() or not np.array_equal(values, values.T)
            or not np.all(np.diag(values) == 1)):
        raise ValueError('SSIM matrix must be finite, square, symmetric with unit diagonal')
    kept, assignments = [], []
    for index in range(len(values)):
        for representative in kept:
            score = float(values[index, representative])
            if score >= threshold:
                assignments.append((representative, score))
                break
        else:
            kept.append(index)
            assignments.append((index, 1.0))
    return kept, assignments


def audit_ssim(output, pages, width=960, height=540, review_threshold=0.995, dedup_threshold=None):
    if width < 11 or height < 11 or not 0 <= review_threshold <= 1:
        raise ValueError('SSIM dimensions must be >=11 and review threshold must be in [0,1]')
    compare, version = require_ssim()
    total_start = time.perf_counter()
    started = time.perf_counter()
    prepared = []
    for page in pages:
        prepared.append(prepare_ssim_image(page['image'], width, height))
    preprocessing = time.perf_counter() - started
    count = len(pages)
    matrix = np.eye(count, dtype=np.float64)
    params = ssim_parameters()
    started = time.perf_counter()
    for i in range(count):
        for j in range(i + 1, count):
            score = float(compare(prepared[i], prepared[j], **params))
            if not np.isfinite(score):
                raise ValueError('SSIM returned a non-finite score')
            matrix[i, j] = matrix[j, i] = score
        if (i + 1) % 8 == 0 or i + 1 == count:
            print(json.dumps({'stage': 'ssim_audit_progress', 'rows': i+1, 'retained_pages': count}), flush=True)
    comparison = time.perf_counter() - started
    labels = [f"page-{p['page']:03d}_sample-{p['sample_id']:06d}" for p in pages]
    with (output/'ssim-matrix.csv').open('w', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(['page_sample'] + labels)
        for label, row in zip(labels, matrix):
            writer.writerow([label] + [f'{value:.12f}' for value in row])
    order = [{key: page[key] for key in ('page', 'sample_id', 'timestamp_seconds', 'image', 'image_sha256')}
             for page in pages]
    pairs = sorted(({'page_a': pages[i]['page'], 'sample_a': pages[i]['sample_id'],
                     'page_b': pages[j]['page'], 'sample_b': pages[j]['sample_id'],
                     'ssim': float(matrix[i, j])}
                    for i in range(count) for j in range(i+1, count)
                    if matrix[i, j] >= review_threshold), key=lambda pair: -pair['ssim'])
    result = {'status': 'completed', 'audit_only': True, 'automatic_deletions': 0,
              'scope': 'retained representatives only; excluded samples are not compared',
              'scikit_image_version': version, 'width': width, 'height': height,
              'grayscale': 'Pillow L', 'resize': 'LANCZOS', 'dtype': 'float32', 'parameters': params,
              'shape': [count, count], 'unique_comparisons': count*(count-1)//2,
              'symmetric': bool(np.array_equal(matrix, matrix.T)),
              'unit_diagonal': bool(np.all(np.diag(matrix) == 1)), 'order': order,
              'matrix_csv': 'ssim-matrix.csv', 'matrix': matrix.tolist(),
              'review_threshold': review_threshold, 'pairs_at_or_above_review_threshold': pairs,
              'limitations': ['Scores are audit evidence, not semantic equality.',
                             'A post-filter matrix cannot detect content already discarded by hashing.'],
              'timings': {'preprocessing_seconds': preprocessing, 'comparison_seconds': comparison}}
    selection_text = 'Explicit audit-only mode: no pages were removed.'
    if dedup_threshold is not None:
        selected, assignments = classify_ssim(matrix, dedup_threshold)
        result.update(audit_only=False, automatic_deletions=count-len(selected),
                      selection={'threshold': dedup_threshold, 'retained_input_indices': selected,
                      'rule': 'score >= threshold to the first retained representative; no transitive closure',
                      'assignments': [{'input_index': i, 'sample_id': pages[i]['sample_id'],
                          'representative_input_index': rep, 'representative_sample_id': pages[rep]['sample_id'],
                          'ssim': score} for i, (rep, score) in enumerate(assignments)]})
        selection_text = f'SSIM >= {dedup_threshold}: {count} inputs → {len(selected)} retained; first representatives only.'
    cells = ['<tr><th></th>' + ''.join(f'<th>{i+1}</th>' for i in range(count)) + '</tr>']
    for i, row in enumerate(matrix):
        cells.append(f'<tr><th>{i+1}</th>' + ''.join(
            f'<td title="{labels[i]} / {labels[j]}: {score:.6f}" '
            f'style="background:rgba(35,105,180,{max(0, min(1, score)):.3f})">{score:.3f}</td>'
            for j, score in enumerate(row)) + '</tr>')
    links = []
    for pair in pairs:
        a, b = pages[pair['page_a']-1], pages[pair['page_b']-1]
        links.append(f"<li>SSIM {pair['ssim']:.6f}: " + ' / '.join(
            f'<a href="{html.escape(Path(p["image"]).as_uri(), quote=True)}">'
            f'page {p["page"]}, sample {p["sample_id"]}, {p["timestamp_seconds"]}s</a>' for p in (a, b)) + '</li>')
    (output/'ssim-audit.html').write_text('<!doctype html><meta charset="utf-8">'
        '<title>Post-filter SSIM audit</title><style>body{font-family:system-ui;margin:24px}'
        'table{border-collapse:collapse;font:10px monospace}td,th{padding:3px;border:1px solid #ddd}'
        '.matrix{overflow:auto;max-height:75vh}</style><h1>Post-filter SSIM audit</h1>'
        f'<p>{count} retained pages, {count*(count-1)//2} comparisons, grayscale {width}×{height}. '
        + html.escape(selection_text) + ' Already hash-discarded content is outside this matrix.</p>'
        f'<h2>Pairs ≥ {review_threshold}</h2><ul>' + ''.join(links) + '</ul>'
        '<h2>Matrix in hash-filtered input order</h2><div class="matrix"><table>' + ''.join(cells) + '</table></div>')
    result['timings']['total_seconds'] = time.perf_counter() - total_start
    (output/'ssim-audit.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    return result
