"""Eligibility from both original temporal neighbours, before either dedup stage."""
import copy
import json
import math
import time

from ssim_audit import prepare_ssim_image, require_ssim, ssim_parameters


def classify_stability(scores, sample_count, threshold):
    if (isinstance(threshold, bool) or not isinstance(threshold, (int, float))
            or not math.isfinite(threshold) or not 0 <= threshold <= 1):
        raise ValueError('stability threshold must be finite and in [0,1]')
    if (type(sample_count) is not int or sample_count < 0
            or len(scores) != max(0, sample_count-1)
            or any(not math.isfinite(s) or not -1 <= s <= 1 for s in scores)):
        raise ValueError('stability requires exactly N-1 finite original adjacent scores in [-1,1]')
    rows = []
    for i in range(sample_count):
        previous = scores[i-1] if i else None
        following = scores[i] if i+1 < sample_count else None
        reasons = []
        for name, score in [('previous', previous), ('next', following)]:
            if score is None:
                reasons.append(f'missing_{name}_neighbour')
            elif score < threshold:
                reasons.append(f'{name}_ssim_below_threshold')
        rows.append({'sample_id': i, 'previous_sample_id': i-1 if i else None,
                     'next_sample_id': i+1 if i+1 < sample_count else None,
                     'ssim_previous': previous, 'ssim_next': following,
                     'eligible': not reasons, 'rejection_reasons': reasons})
    return rows


def audit_stability(output, paths, interval, width=960, height=540, threshold=.995, skip=False):
    # Validate even in explicit bypass mode, so invalid arguments are never hidden.
    classify_stability([], 0, threshold)
    if width < 11 or height < 11:
        raise ValueError('stability SSIM dimensions must be >=11')
    started_all = time.perf_counter()
    scores, previous = [], None
    preprocessing = comparison = 0.0
    version = None
    if not skip:
        compare, version = require_ssim()
        for i, path in enumerate(paths):
            started = time.perf_counter()
            current = prepare_ssim_image(path, width, height)
            preprocessing += time.perf_counter()-started
            if previous is not None:
                started = time.perf_counter()
                scores.append(float(compare(previous, current, **ssim_parameters())))
                comparison += time.perf_counter()-started
            previous = current  # rolling cache: every image prepared once, every adjacent pair once
            if (i+1) % 100 == 0 or i+1 == len(paths):
                print(json.dumps({'stage': 'stability_progress', 'samples': i+1,
                                  'adjacent_comparisons': len(scores)}), flush=True)
        rows = classify_stability(scores, len(paths), threshold)
    else:
        rows = [{'sample_id': i, 'previous_sample_id': i-1 if i else None,
                 'next_sample_id': i+1 if i+1 < len(paths) else None,
                 'ssim_previous': None, 'ssim_next': None,
                 'eligible': True, 'rejection_reasons': [], 'explicitly_skipped': True}
                for i in range(len(paths))]
    for row, path in zip(rows, paths):
        row.update(timestamp_seconds=row['sample_id']*interval, image=str(path))
    eligible = [r['sample_id'] for r in rows if r['eligible']]
    result = {'status': 'skipped' if skip else 'completed', 'sample_count': len(paths),
              'eligible_count': len(eligible), 'rejected_count': len(paths)-len(eligible),
              'eligible_sample_ids': eligible, 'threshold': threshold, 'width': width, 'height': height,
              'interval_seconds': interval, 'parameters': ssim_parameters(),
              'scikit_image_version': version, 'unique_adjacent_comparisons': len(scores),
              'rule': 'both original immediate neighbours must have SSIM >= threshold; endpoints rejected',
              'limitations': ['Three sampled points do not prove continuous stability between them.',
                             'Short or changing real pages may have no eligible middle sample.'],
              'samples': rows, 'timings': {'preprocessing_seconds': preprocessing,
                  'comparison_seconds': comparison, 'total_seconds': time.perf_counter()-started_all}}
    if not eligible:
        result['status'] = 'no_eligible_samples'
        result['diagnostic'] = ('Fewer than 3 samples cannot have two original neighbours.' if len(paths)<3
                                else 'No sample passes both original-neighbour SSIM comparisons.')
    (output/'stability.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    (output/'stage-status.json').write_text(json.dumps({k: result[k] for k in
        ('status', 'sample_count', 'eligible_count', 'rejected_count')}, indent=2)+'\n')
    return result


def restore_source_ids(samples, pages, eligible_ids, interval, stability_rows):
    """Project dense working indices back to the complete original fixed-grid sequence."""
    if (len(samples) != len(eligible_ids) or eligible_ids != sorted(set(eligible_ids))
            or eligible_ids != [r['sample_id'] for r in stability_rows if r['eligible']]):
        raise ValueError('eligible mapping must match the ordered original stability decisions')
    id_keys = ('sample_id', 'representative_sample_id', 'hash_representative_sample_id',
               'ssim_input_sample_id', 'ssim_representative_sample_id')

    def project(row):
        row = copy.deepcopy(row)
        for key in id_keys:
            if key in row:
                row[key] = eligible_ids[row[key]]
        row['timestamp_seconds'] = row['sample_id']*interval
        return row

    active = {eligible_ids[i]: project(s) for i, s in enumerate(samples)}
    complete = []
    for decision in stability_rows:
        i = decision['sample_id']
        row = active.get(i, {'sample_id': i, 'timestamp_seconds': i*interval, 'image': decision['image'],
            'phash': None, 'dhash': None, 'retained': False, 'representative_sample_id': None,
            'page': None, 'hash_representative_sample_id': None, 'ssim_input_sample_id': None,
            'ssim_representative_sample_id': None})
        row['stability'] = copy.deepcopy(decision)
        row['status'] = ('stability_rejected' if not decision['eligible'] else
                         'retained' if row['retained'] else 'duplicate')
        complete.append(row)
    output_pages = []
    for page in pages:
        page = project(page)
        page['occurrences'] = [project(o) for o in page['occurrences']]
        output_pages.append(page)
    return complete, output_pages


def restore_decisions(decisions, eligible_ids, interval):
    result = copy.deepcopy(decisions)
    for row in result:
        for key in ('sample_id', 'representative_sample_id'):
            row[key] = eligible_ids[row[key]]
        row['timestamp_seconds'] = row['sample_id']*interval
        row['representative_timestamp_seconds'] = row['representative_sample_id']*interval
    return result
