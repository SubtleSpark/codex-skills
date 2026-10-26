import contextlib
import copy
import csv
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'scripts'))
from auto_pdf import compose_stages, frame_records, main, sha256, verify_pptx
from ssim_audit import audit_ssim, classify_ssim, require_ssim
from test_auto_pdf import fixture


class AuditTest(unittest.TestCase):
    def test_ssim_threshold_is_inclusive_and_discarded_inputs_cannot_bridge(self):
        matrix = [[1, .995, .994], [.995, 1, .999], [.994, .999, 1]]
        kept, assignments = classify_ssim(matrix, .995)
        self.assertEqual(kept, [0, 2])
        self.assertEqual(assignments, [(0, 1.0), (0, .995), (2, 1.0)])
        self.assertEqual(classify_ssim(matrix, .9999)[0], [0, 1, 2])

    def test_first_matching_representative_wins_over_nearest(self):
        matrix = [[1, .99, .997], [.99, 1, .999], [.997, .999, 1]]
        self.assertEqual(classify_ssim(matrix, .995)[1][2][0], 0)
        for score in [.6180285298405862, .9892165768276466]:
            self.assertEqual(classify_ssim([[1, score], [score, 1]], .995)[0], [0, 1])

    def test_invalid_matrix_or_threshold_is_rejected(self):
        for value in [-.1, 1.1, float('nan'), True]:
            with self.assertRaises(ValueError):
                classify_ssim([[1]], value)
        for matrix in [[[1, .9]], [[1, .9], [.8, 1]], [[.9]], [[1, float('nan')], [float('nan'), 1]]]:
            with self.assertRaises(ValueError):
                classify_ssim(matrix, .995)

    def test_composed_mapping_preserves_both_stages_and_first_image(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = []
            for i in range(3):
                path = root/f'{i}.png'
                fixture('left').save(path)
                paths.append(path)
            ps, ds = [0, 0, 7], [0, 0, 7]
            old_samples, old_pages = frame_records(paths, ps, [(0, 0), (0, 0), (2, 0)], 5, ds)
            samples, pages, decisions = compose_stages(paths, ps, old_samples, old_pages,
                                                       [(0, 1), (0, .998)], 5, ds)
            self.assertEqual([s['representative_sample_id'] for s in samples], [0, 0, 0])
            self.assertEqual([s['hash_representative_sample_id'] for s in samples], [0, 0, 2])
            self.assertEqual(samples[2]['ssim_input_sample_id'], 2)
            self.assertEqual(samples[2]['ssim_score_hash_rep_to_final_rep'], .998)
            self.assertEqual(samples[2]['distance_to_representative'], 3)
            self.assertEqual(samples[2]['phash_distance_to_hash_representative'], 0)
            self.assertEqual(pages[0]['image'], str(paths[0]))
            self.assertEqual([o['sample_id'] for o in pages[0]['occurrences']], [0, 1, 2])
            self.assertEqual(decisions[1]['representative_sample_id'], 0)

    def test_default_ssim_really_reduces_pdf_and_pptx_and_keeps_stage_maps(self):
        from PIL import ImageDraw
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = []
            for i, cursor in enumerate([150, 270, None]):
                image = fixture('left' if cursor is not None else 'right')
                if cursor is not None:
                    ImageDraw.Draw(image).rectangle((cursor, 100, cursor+3, 103), fill='black')
                path = root/f'{i}.png'; image.save(path); paths.append(path)
            video = root/'source.mp4'; video.write_bytes(b'cached provenance')
            manifest = root/'input.json'
            manifest.write_text(json.dumps({'sampling': 'all fixed-grid samples; no MAE prefilter',
                'sample_count': 3, 'interval_seconds': 5, 'duration_seconds': 11,
                'source_video_sha256': sha256(video),
                'samples': [{'sample_id': i, 'timestamp_seconds': i*5, 'image': str(p)} for i, p in enumerate(paths)]}))
            output = root/'out'
            command = ['auto_pdf.py', str(video), str(output), '--samples-manifest', str(manifest), '--skip-stability', '--format', 'both']
            with patch('sys.argv', command), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(main(), 0)
            run = json.loads((output/'run.json').read_text())
            self.assertEqual((run['hash_retained_pages'], run['retained_pages'], run['ssim_merges']), (3, 2, 1))
            self.assertFalse(run['ssim_audit']['audit_only'])
            self.assertEqual(run['ssim_audit']['automatic_deletions'], 1)
            self.assertEqual(len(json.loads((output/'hash-pages.json').read_text())['pages']), 3)
            pages = json.loads((output/'pages.json').read_text())['pages']
            self.assertEqual([p['sample_id'] for p in pages], [0, 2])
            self.assertTrue(run['validation']['exports']['pdf']['ordered_source_pixels_preserved'])
            self.assertEqual(verify_pptx(output/'slides.pptx', pages)['page_count'], 2)
            sample = json.loads((output/'samples.json').read_text())['samples'][1]
            self.assertEqual((sample['hash_representative_sample_id'], sample['representative_sample_id']), (1, 0))
            self.assertGreaterEqual(sample['ssim_score_hash_rep_to_final_rep'], .995)

    def pages(self, root):
        pages = []
        for number, (sample, layout) in enumerate([(10, 'left'), (20, 'right'), (30, 'left')], 1):
            path = root/f'{sample}.png'
            fixture(layout).save(path)
            pages.append({'page': number, 'sample_id': sample, 'timestamp_seconds': sample*5,
                          'image': str(path), 'image_sha256': sha256(path)})
        return pages

    def test_matrix_identity_symmetry_order_and_no_selection_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pages = self.pages(root)
            before = copy.deepcopy(pages)
            with contextlib.redirect_stdout(io.StringIO()):
                result = audit_ssim(root, pages, 96, 54, .99)
            self.assertEqual(pages, before)
            self.assertTrue(result['audit_only'])
            self.assertEqual(result['automatic_deletions'], 0)
            self.assertEqual(result['unique_comparisons'], 3)
            matrix = result['matrix']
            self.assertEqual(matrix[0][2], 1)
            self.assertLess(matrix[0][1], .99)
            for i in range(3):
                self.assertEqual(matrix[i][i], 1)
                for j in range(3):
                    self.assertEqual(matrix[i][j], matrix[j][i])
            self.assertEqual([p['sample_id'] for p in result['order']], [10, 20, 30])
            with (root/'ssim-matrix.csv').open() as stream:
                rows = list(csv.reader(stream))
            self.assertEqual(len(rows), 4)
            self.assertEqual(rows[0][2], 'page-002_sample-000020')
            self.assertEqual(rows[2][0], rows[0][2])

    def test_missing_ssim_is_an_explicit_error(self):
        with patch('ssim_audit.importlib.import_module', side_effect=ImportError('missing')):
            with self.assertRaisesRegex(ValueError, '--skip-ssim-audit'):
                require_ssim()

    def test_skip_audit_and_both_exports_from_existing_samples(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = self.pages(root)
            video = root/'source.mp4'
            video.write_bytes(b'cached-input-provenance')
            manifest = root/'samples.json'
            manifest.write_text(json.dumps({'sampling': 'all fixed-grid samples; no MAE prefilter',
                'sample_count': 3, 'interval_seconds': 5, 'duration_seconds': 11,
                'source_video_sha256': sha256(video),
                'samples': [{'sample_id': i, 'timestamp_seconds': i*5, 'image': p['image']}
                            for i, p in enumerate(original)]}))
            output = root/'out'
            command = ['auto_pdf.py', str(video), str(output), '--samples-manifest', str(manifest),
                       '--skip-stability', '--skip-ssim-audit', '--format', 'both']
            with patch('sys.argv', command), patch('ssim_audit.require_ssim') as dependency, contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(main(), 0)
                dependency.assert_not_called()
            run = json.loads((output/'run.json').read_text())
            self.assertEqual(run['ssim_audit']['status'], 'skipped')
            self.assertFalse((output/'ssim-matrix.csv').exists())
            self.assertEqual(run['retained_pages'], 2)
            self.assertTrue(run['validation']['exports']['pdf']['ordered_source_pixels_preserved'])
            self.assertTrue(run['validation']['exports']['pptx']['ordered_source_pixels_preserved'])
            pages = json.loads((output/'pages.json').read_text())['pages']
            self.assertEqual([p['sample_id'] for p in pages], [0, 1])
            self.assertEqual(verify_pptx(output/'slides.pptx', pages)['page_count'], 2)
            blocked = root/'missing-dependency'
            command[2] = str(blocked)
            command.remove('--skip-ssim-audit')
            with patch('sys.argv', command), patch('ssim_audit.require_ssim', side_effect=ValueError('missing SSIM')), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    main()
            self.assertFalse(blocked.exists())


if __name__ == '__main__':
    unittest.main()
