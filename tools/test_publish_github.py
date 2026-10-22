"""Check the source allowlist without any network or repository mutations."""
import tempfile, unittest, subprocess
from unittest.mock import patch
from pathlib import Path
from publish_github import source_files, ensure_repository

class PublishSourceTest(unittest.TestCase):
    def test_private_signing_material_and_builds_are_not_included(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            for name in ['README.md','app/src/main/AndroidManifest.xml','.signing/credentials.json',
                         '.signing/binnder-release.jks','app/build/outputs/app.apk','dist/app.apk']:
                p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('fixture')
            self.assertEqual(set(map(str,source_files(root))),{'README.md','app/src/main/AndroidManifest.xml'})
    def test_symlink_cannot_upload_a_private_file(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);p=root/'app/src/main/leak.txt';p.parent.mkdir(parents=True)
            secret=root/'private';secret.write_text('fixture');p.symlink_to(secret)
            with self.assertRaises(ValueError):source_files(root)
    def test_signing_key_in_an_allowed_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);p=root/'app/src/main/assets/release.jks';p.parent.mkdir(parents=True);p.write_text('fixture')
            with self.assertRaises(ValueError):source_files(root)

class RepositoryCreationTest(unittest.TestCase):
    @patch('publish_github.command')
    @patch('publish_github.subprocess.run')
    def test_connectivity_error_does_not_create_repository(self, run, command):
        run.return_value=subprocess.CompletedProcess([],1,'','error connecting to api.github.com')
        with self.assertRaises(SystemExit):ensure_repository('owner/Binnder',create=True)
        command.assert_not_called()

    @patch('publish_github.command')
    @patch('publish_github.subprocess.run')
    def test_missing_repository_is_created_only_with_explicit_flag(self, run, command):
        run.return_value=subprocess.CompletedProcess([],1,'','gh: Not Found (HTTP 404)')
        with self.assertRaises(SystemExit):ensure_repository('owner/Binnder')
        command.assert_not_called()
        command.side_effect=['','{"nameWithOwner":"owner/Binnder"}']
        self.assertEqual(ensure_repository('owner/Binnder',create=True)['nameWithOwner'],'owner/Binnder')
        self.assertEqual(command.call_args_list[0].args[0][:5],['gh','repo','create','owner/Binnder','--public'])

    @patch('publish_github.command', return_value='{"nameWithOwner":"owner/Binnder"}')
    @patch('publish_github.subprocess.run')
    def test_existing_repository_is_reused(self, run, command):
        run.return_value=subprocess.CompletedProcess([],0,'{}','')
        ensure_repository('owner/Binnder',create=True)
        command.assert_called_once()
        self.assertEqual(command.call_args.args[0][:3],['gh','repo','view'])

if __name__=='__main__':unittest.main()
