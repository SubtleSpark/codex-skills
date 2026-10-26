import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'scripts'))
from auto_pdf import main, sha256
from stability_filter import audit_stability, classify_stability
from test_auto_pdf import fixture


class StabilityTest(unittest.TestCase):
    def test_original_two_neighbours_and_inclusive_threshold(self):
        rows = classify_stability([.995, .995, .994, .994, 1, 1], 7, .995)
        self.assertEqual([r['sample_id'] for r in rows if r['eligible']], [1, 5])
        self.assertEqual(rows[0]['rejection_reasons'], ['missing_previous_neighbour'])
        self.assertEqual(rows[6]['rejection_reasons'], ['missing_next_neighbour'])
        self.assertEqual(rows[3]['rejection_reasons'], ['previous_ssim_below_threshold', 'next_ssim_below_threshold'])
        self.assertEqual(rows[2]['next_sample_id'], 3)  # never jump over a rejected sample

    def test_short_segments_and_endpoints_are_not_relaxed(self):
        for count, scores in [(0, []), (1, []), (2, [1]), (3, [.9, .9])]:
            self.assertFalse(any(r['eligible'] for r in classify_stability(scores, count, .995)))
        self.assertEqual([r['eligible'] for r in classify_stability([1, 1], 3, 1)], [False, True, False])
        for threshold in [float('nan'), -.01, 1.01, True]:
            with self.assertRaises(ValueError):
                classify_stability([], 0, threshold)
        for scores in [[1], [1, float('nan')], [1, 1.1]]:
            with self.assertRaises(ValueError):
                classify_stability(scores, 3, .995)

    def test_adjacent_pairs_once_and_each_preprocessing_once(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(f'{i}.png') for i in range(5)]
            compared = []
            def compare(a, b, **parameters):
                compared.append((a, b))
                return .999
            with patch('stability_filter.require_ssim', return_value=(compare, 'test')), \
                 patch('stability_filter.prepare_ssim_image', side_effect=range(5)) as prepare, \
                 contextlib.redirect_stdout(io.StringIO()):
                result = audit_stability(Path(directory), paths, 5)
            self.assertEqual(prepare.call_count, 5)
            self.assertEqual(compared, [(0, 1), (1, 2), (2, 3), (3, 4)])
            self.assertEqual(result['unique_adjacent_comparisons'], 4)
            self.assertEqual(result['eligible_sample_ids'], [1, 2, 3])
            self.assertIsNone(result['samples'][0]['ssim_previous'])
            self.assertEqual(result['samples'][2]['ssim_previous'], .999)

    def test_no_eligible_samples_record_diagnostic_and_do_not_export(self):
        for names in [['left', 'right'], ['left', 'right', 'left']]:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                video, manifest = self.make_manifest(root, names)
                output = root/'out'
                with patch('sys.argv', ['auto_pdf.py', str(video), str(output), '--samples-manifest', str(manifest)]), \
                     contextlib.redirect_stdout(io.StringIO()):
                    with self.assertRaisesRegex(ValueError, 'no fallback or export'):
                        main()
                stability = json.loads((output/'stability.json').read_text())
                self.assertEqual(stability['status'], 'no_eligible_samples')
                self.assertEqual(stability['eligible_count'], 0)
                self.assertFalse((output/'slides.pdf').exists())
                self.assertEqual(len(stability['samples']), len(names))

    @staticmethod
    def make_manifest(root, names):
        rows = []
        for i, layout in enumerate(names):
            path = root/f'{i}.png'; fixture(layout).save(path)
            rows.append({'sample_id': i, 'timestamp_seconds': i*5, 'image': str(path)})
        video = root/'source.mp4'; video.write_bytes(b'cached provenance')
        manifest = root/'input.json'
        manifest.write_text(json.dumps({'sampling': 'all fixed-grid samples; no MAE prefilter',
            'sample_count': len(names), 'interval_seconds': 5, 'duration_seconds': len(names)*5-1,
            'source_video_sha256': sha256(video),
            'samples': rows}))
        return video, manifest

    def test_full_pipeline_retains_first_qualified_frame_and_all_stage_maps(self):
        names = ['left']*3 + ['other'] + ['right']*3 + ['other'] + ['left']*3
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            video, manifest = self.make_manifest(root, names)
            output = root/'out'
            with patch('sys.argv', ['auto_pdf.py', str(video), str(output), '--samples-manifest', str(manifest),
                                    '--format', 'both']), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(main(), 0)
            run = json.loads((output/'run.json').read_text())
            self.assertEqual((run['sample_count'], run['stability_eligible_samples'], run['hash_retained_pages'],
                              run['retained_pages'], run['automatic_merges']), (11, 3, 2, 2, 1))
            rows = json.loads((output/'samples.json').read_text())['samples']
            self.assertEqual([r['sample_id'] for r in rows], list(range(11)))
            self.assertEqual([r['sample_id'] for r in rows if r['retained']], [1, 5])
            self.assertEqual(rows[9]['hash_representative_sample_id'], 1)
            self.assertEqual(rows[9]['ssim_input_sample_id'], 1)
            self.assertEqual(rows[9]['representative_sample_id'], 1)
            self.assertEqual(rows[0]['status'], 'stability_rejected')
            self.assertIsNone(rows[0]['page'])
            self.assertIsNone(rows[3]['representative_sample_id'])
            self.assertEqual(rows[9]['timestamp_seconds'], 45)
            pages = json.loads((output/'pages.json').read_text())['pages']
            self.assertEqual([p['timestamp_seconds'] for p in pages], [5, 25])
            self.assertEqual([o['sample_id'] for o in pages[0]['occurrences']], [1, 9])
            audit = json.loads((output/'ssim-audit.json').read_text())
            self.assertEqual([r['sample_id'] for r in audit['order']], [1, 5])
            decisions = json.loads((output/'ssim-decisions.json').read_text())['decisions']
            self.assertEqual([d['sample_id'] for d in decisions], [1, 5])
            self.assertEqual(decisions[1]['representative_timestamp_seconds'], 25)
            self.assertEqual(run['validation']['exports']['pptx']['page_count'], 2)
            self.assertTrue(run['validation']['exports']['pdf']['ordered_source_pixels_preserved'])

    def test_missing_ssim_not_hidden_by_skipping_only_final_dedup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            video, manifest = self.make_manifest(root, ['left']*3)
            with patch('sys.argv', ['auto_pdf.py', str(video), str(root/'out'), '--samples-manifest', str(manifest),
                                    '--skip-ssim']), \
                 patch('ssim_audit.require_ssim', side_effect=ValueError('missing SSIM')), \
                 contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    main()
            self.assertFalse((root/'out').exists())

    def test_reused_samples_require_matching_video_sha256_before_creating_output(self):
        for digest, message in [(None, 'must include source_video_sha256'), ('0'*64, 'SHA256 differs')]:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                video, manifest = self.make_manifest(root, ['left']*3)
                data = json.loads(manifest.read_text())
                if digest is None:
                    del data['source_video_sha256']
                else:
                    data['source_video_sha256'] = digest
                manifest.write_text(json.dumps(data))
                errors = io.StringIO()
                with patch('sys.argv', ['auto_pdf.py', str(video), str(root/'out'),
                                       '--samples-manifest', str(manifest)]), contextlib.redirect_stderr(errors):
                    with self.assertRaises(SystemExit):
                        main()
                self.assertIn(message, errors.getvalue())
                self.assertFalse((root/'out').exists())


if __name__ == '__main__':
    unittest.main()
