import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from PIL import Image, ImageDraw
import pypdf

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from auto_pdf import (DEFAULT_DISTANCE, classify_dual_hashes, classify_hashes, dhash,
                      frame_records, hamming, phash, load_samples, verify_grid_count)


def fixture(layout):
    image = Image.new('RGB', (320, 180), 'white')
    draw = ImageDraw.Draw(image)
    draw.rectangle((20, 10, 300, 20), fill='black')
    if layout == 'left':
        draw.rectangle((15, 40, 125, 155), fill='black')
    elif layout == 'right':
        draw.rectangle((195, 40, 305, 155), fill='black')
    else:
        draw.ellipse((130, 30, 190, 90), fill='black')
        draw.rectangle((20, 130, 300, 160), fill='black')
    return image


class PerceptualHashTest(unittest.TestCase):
    def test_same_and_recompressed_images_merge_but_different_layout_survives(self):
        first = fixture('left')
        buffer = io.BytesIO()
        first.save(buffer, 'JPEG', quality=70)
        buffer.seek(0)
        with Image.open(buffer) as near:
            a, b, c = phash(first), phash(near), phash(fixture('right'))
        self.assertEqual(phash(first.copy()), a)
        self.assertLessEqual(hamming(a, b), DEFAULT_DISTANCE)
        self.assertGreater(hamming(a, c), DEFAULT_DISTANCE)
        self.assertLess(a, 1 << 64)
        kept, assignments = classify_hashes([a, b, c], DEFAULT_DISTANCE)
        self.assertEqual(kept, [0, 2])
        self.assertEqual(assignments[1][0], 0)

    def test_revisits_keep_first_frame_and_first_seen_order(self):
        a, b = phash(fixture('left')), phash(fixture('right'))
        kept, assignments = classify_hashes([a, b, a, b, a], 0)
        self.assertEqual(kept, [0, 1])
        self.assertEqual([x[0] for x in assignments], [0, 1, 0, 1, 0])

    def test_inclusive_threshold_and_direction(self):
        self.assertEqual(hamming(0, 0b111), 3)
        self.assertEqual(classify_hashes([0, 0b111], 3)[0], [0])
        self.assertEqual(classify_hashes([0, 0b111], 2)[0], [0, 1])
        self.assertEqual(classify_hashes([7, 7], 0)[0], [0])
        for invalid in [-1, 65, True, 1.5]:
            with self.assertRaises(ValueError):
                classify_hashes([0], invalid)

    def test_discarded_frame_does_not_bridge_two_representatives(self):
        # B is close to A and C, but A and C are too different to merge.
        kept, assignments = classify_hashes([0, 0b111, 0b111111], 3)
        self.assertEqual(kept, [0, 2])
        self.assertEqual([x[0] for x in assignments], [0, 0, 2])


class AutomaticPipelineTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'FFmpeg required')
    def test_all_fixed_samples_export_without_ai_decisions(self):
        script = Path(__file__).resolve().parents[1] / 'scripts/auto_pdf.py'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ['left', 'right', 'other']:
                fixture(name).save(root / f'{name}.png')
            cmd = ['ffmpeg', '-nostdin', '-v', 'error']
            for name, duration in [('left', 5), ('right', 5), ('left', 5), ('other', 1)]:
                cmd += ['-loop', '1', '-framerate', '5', '-t', str(duration), '-i', str(root/f'{name}.png')]
            cmd += ['-filter_complex', '[0:v][1:v][2:v][3:v]concat=n=4:v=1:a=0[v]',
                    '-map', '[v]', '-c:v', 'ffv1', str(root/'source.mkv')]
            subprocess.run(cmd, check=True, capture_output=True)
            output = root / 'automatic'
            result = subprocess.run([sys.executable, '-B', str(script), str(root/'source.mkv'), str(output), '--skip-stability'],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            samples = json.loads((output/'samples.json').read_text())
            pages = json.loads((output/'pages.json').read_text())
            run = json.loads((output/'run.json').read_text())
            self.assertEqual(samples['interval_seconds'], 5)
            self.assertEqual(samples['hash_mode'], 'phash-dhash')
            self.assertEqual(samples['hamming_threshold'], 1)
            self.assertEqual(samples['dhash_hamming_threshold'], 2)
            self.assertEqual(samples['sample_count'], 4)
            self.assertEqual(len(list((output/'samples').glob('*.png'))), 4)
            self.assertEqual([s['timestamp_seconds'] for s in samples['samples']], [0, 5, 10, 15])
            self.assertEqual([p['timestamp_seconds'] for p in pages['pages']], [0, 5, 15])
            self.assertEqual(samples['samples'][2]['representative_sample_id'], 0)
            self.assertEqual(pages['pages'][0]['sample_id'], 0)
            self.assertFalse(run['ai_decisions_used'])
            self.assertFalse(run['semantic_postprocessing'])
            self.assertTrue(run['validation']['ordered_source_pixels_preserved'])
            self.assertEqual(len(pypdf.PdfReader(output/'slides.pdf').pages), 3)
            audit = json.loads((output/'ssim-audit.json').read_text())
            self.assertEqual(audit['shape'], [3, 3])
            self.assertEqual(audit['unique_comparisons'], 3)
            self.assertEqual([p['sample_id'] for p in audit['order']], [0, 1, 3])
            again = subprocess.run([sys.executable, '-B', str(script), str(root/'source.mkv'), str(output), '--skip-stability'],
                                   capture_output=True, text=True)
            self.assertNotEqual(again.returncode, 0)
            self.assertIn('output exists', again.stderr)
            dual = root / 'dual'
            result = subprocess.run([sys.executable, '-B', str(script), str(root/'source.mkv'), str(dual),
                                     '--skip-stability', '--hash-mode', 'phash-dhash', '--hash-distance', '6', '--dhash-distance', '2'],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            dual_samples = json.loads((dual/'samples.json').read_text())
            self.assertEqual(dual_samples['hash_mode'], 'phash-dhash')
            self.assertEqual(dual_samples['samples'][2]['representative_sample_id'], 0)
            self.assertEqual(dual_samples['dhash_hamming_threshold'], 2)
            self.assertEqual(len(pypdf.PdfReader(dual/'slides.pdf').pages), 3)


class DualHashTest(unittest.TestCase):
    def test_real_chart_regression_requires_dhash_confirmation(self):
        # BV1pSnT6yEHT samples 279 (RB transition) / 289 (glass/soda chart).
        ps = [int(value, 16) for value in ['955635565677464c', '95523f565273464d']]
        ds = [int(value, 16) for value in ['5c9ae2ccac9e9e86', 'dcbaa28cb4eaa286']]
        self.assertEqual((hamming(*ps), hamming(*ds)), (6, 14))
        self.assertEqual(classify_dual_hashes(ps, ds, 6, 2)[0], [0, 1])

    def test_real_title_collision_remains_a_documented_limit(self):
        # Different titles 25/234 collide in dHash; dual hashes do not prove content equality.
        ps = [int(value, 16) for value in ['d0565c5c767256cc', 'd4565c5c7672568c']]
        ds = [int('84808c8c80848080', 16)] * 2
        self.assertEqual((hamming(*ps), hamming(*ds)), (2, 0))
        self.assertEqual(classify_dual_hashes(ps, ds, 6, 2)[0], [0])

    def test_dhash_has_documented_direction_and_64_bits(self):
        ascending = Image.new('L', (9, 8))
        ascending.putdata([column * 20 for row in range(8) for column in range(9)])
        descending = Image.new('L', (9, 8))
        descending.putdata([255 - column * 20 for row in range(8) for column in range(9)])
        self.assertEqual(dhash(ascending), (1 << 64) - 1)
        self.assertEqual(dhash(descending), 0)
        self.assertEqual(dhash(Image.new('L', (9, 8), 255)), 0)

    def test_identical_near_images_merge_and_different_layout_survives(self):
        a = fixture('left')
        buffer = io.BytesIO()
        a.save(buffer, 'JPEG', quality=70)
        buffer.seek(0)
        with Image.open(buffer) as near:
            images = [a, near.copy(), fixture('right')]
        ps, ds = [phash(i) for i in images], [dhash(i) for i in images]
        kept, assignments = classify_dual_hashes(ps, ds, 6, 2)
        self.assertEqual(kept, [0, 2])
        self.assertEqual(assignments[1][0], 0)

    def test_both_thresholds_inclusive_and_required(self):
        self.assertEqual(classify_dual_hashes([0, 7], [0, 3], 3, 2)[0], [0])
        self.assertEqual(classify_dual_hashes([0, 7], [0, 3], 2, 2)[0], [0, 1])
        self.assertEqual(classify_dual_hashes([0, 7], [0, 3], 3, 1)[0], [0, 1])

    def test_hashes_must_match_the_same_representative(self):
        kept, assignments = classify_dual_hashes([0, 255, 0], [0, 255, 255], 0, 0)
        self.assertEqual(kept, [0, 1, 2])

    def test_revisit_and_no_discarded_bridge(self):
        kept, assignments = classify_dual_hashes([0, 7, 63, 0, 63], [0]*5, 3, 0)
        self.assertEqual(kept, [0, 2])
        self.assertEqual([a[0] for a in assignments], [0, 0, 2, 0, 2])

    def test_invalid_lengths_and_thresholds_rejected(self):
        with self.assertRaises(ValueError):
            classify_dual_hashes([0], [], 0, 0)
        for value in [-1, 65, True, 1.5]:
            for p, d in [(value, 0), (0, value)]:
                with self.assertRaises(ValueError):
                    classify_dual_hashes([0], [0], p, d)
        with self.assertRaises(ValueError):
            frame_records([], [0], [], 5)

    def test_existing_sample_manifest_rejects_missing_samples_and_wrong_order(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ['left', 'right', 'other']:
                fixture(name).save(root/f'{name}.png')
            manifest = root/'input.json'
            rows = [{'sample_id': i, 'timestamp_seconds': i*5, 'image': str(root/f'{name}.png')}
                    for i, name in enumerate(['left', 'right', 'left', 'other'])]
            data = {'sampling': 'all fixed-grid samples; no MAE prefilter', 'sample_count': 4,
                    'interval_seconds': 5, 'duration_seconds': 16, 'samples': rows}
            manifest.write_text(json.dumps(data))
            self.assertEqual(len(load_samples(manifest)[1]), 4)
            verify_grid_count(rows, 16, 5)
            with self.assertRaisesRegex(ValueError, 'incomplete fixed video grid'):
                verify_grid_count(rows[:3], 16, 5)
            data['samples'] = rows[:3]
            data['sample_count'] = 3
            manifest.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, 'every fixed-grid'):
                load_samples(manifest)
            data['samples'], data['sample_count'] = rows, 4
            data['samples'][1]['sample_id'] = 7
            manifest.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, 'contiguous'):
                load_samples(manifest)


if __name__ == '__main__':
    unittest.main()
