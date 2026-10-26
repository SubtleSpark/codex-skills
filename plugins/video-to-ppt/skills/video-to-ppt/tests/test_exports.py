from pathlib import Path
import sys
import tempfile
import unittest
from xml.etree import ElementTree
from zipfile import ZipFile

from pypdf import PdfWriter

sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'scripts'))
from auto_pdf import export_pdf, export_pptx, verify_pdf, verify_pptx
from test_auto_pdf import fixture


class ExportValidationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.pages = []
        for number, layout in enumerate(['left', 'right'], 1):
            path = self.root/f'{number}.png'; fixture(layout).save(path)
            self.pages.append({'page': number, 'sample_id': number, 'timestamp_seconds': number*5,
                               'image': str(path)})

    def export(self, path, pages):
        if path.suffix == '.pdf':
            export_pdf(path, pages, 320, 180, 'Export regression', '')
        else:
            export_pptx(path, pages, 320, 180, 'Export regression')

    def test_wrong_page_order_is_rejected_by_both_validators(self):
        for suffix, verify in [('pdf', verify_pdf), ('pptx', verify_pptx)]:
            with self.subTest(format=suffix):
                path = self.root/f'reversed.{suffix}'
                self.export(path, list(reversed(self.pages)))
                with self.assertRaisesRegex(ValueError, 'differs'):
                    verify(path, self.pages)

    def test_missing_page_is_rejected_by_both_validators(self):
        for suffix, verify in [('pdf', verify_pdf), ('pptx', verify_pptx)]:
            with self.subTest(format=suffix):
                path = self.root/f'missing.{suffix}'
                self.export(path, self.pages[:1])
                with self.assertRaisesRegex(ValueError, 'count differs'):
                    verify(path, self.pages)

    def test_wrong_pdf_image_binding_and_pptx_relationship_are_rejected(self):
        good_pdf, bad_pdf = self.root/'good.pdf', self.root/'wrong-binding.pdf'
        self.export(good_pdf, self.pages)
        self.assertEqual(verify_pdf(good_pdf, self.pages)['page_count'], 2)
        writer = PdfWriter(clone_from=good_pdf)
        first = writer.pages[0]['/Resources']['/XObject']
        second = writer.pages[1]['/Resources']['/XObject']
        first[next(iter(first))] = next(iter(second.values()))
        with bad_pdf.open('wb') as stream:
            writer.write(stream)
        with self.assertRaisesRegex(ValueError, 'differs'):
            verify_pdf(bad_pdf, self.pages)

        good_pptx, bad_pptx = self.root/'good.pptx', self.root/'wrong-relationship.pptx'
        self.export(good_pptx, self.pages)
        self.assertEqual(verify_pptx(good_pptx, self.pages)['page_count'], 2)
        first_name = 'ppt/slides/_rels/slide1.xml.rels'
        second_name = 'ppt/slides/_rels/slide2.xml.rels'
        with ZipFile(good_pptx) as original:
            first, second = [ElementTree.fromstring(original.read(n)) for n in [first_name, second_name]]
            image_target = next(r.get('Target') for r in second if r.get('Type', '').endswith('/image'))
            next(r for r in first if r.get('Type', '').endswith('/image')).set('Target', image_target)
            with ZipFile(bad_pptx, 'w') as changed:
                for entry in original.infolist():
                    changed.writestr(entry, ElementTree.tostring(first) if entry.filename == first_name
                                     else original.read(entry.filename))
        with self.assertRaisesRegex(ValueError, 'differs'):
            verify_pptx(bad_pptx, self.pages)


if __name__ == '__main__':
    unittest.main()
