#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read only metadata from the fresh probe simulator; never modify provider databases."""
from pathlib import Path
import json
import plistlib
import shutil
import sqlite3
import sys

data = Path(sys.argv[1])
out = Path(sys.argv[2])
records = []
groups = data / 'Containers/Shared/AppGroup'
roots = []
for folder in groups.iterdir() if groups.exists() else []:
    meta = folder / '.com.apple.mobile_container_manager.metadata.plist'
    if not meta.exists():
        continue
    with meta.open('rb') as f:
        identifier = plistlib.load(f).get('MCMMetadataIdentifier', '')
    if identifier != 'group.com.apple.FileProvider.LocalStorage':
        continue
    roots.append((identifier, folder))
plugins = data / 'Containers/Data/PluginKitPlugin'
for folder in plugins.iterdir() if plugins.exists() else []:
    meta = folder / '.com.apple.mobile_container_manager.metadata.plist'
    if meta.exists():
        with meta.open('rb') as f:
            identifier = plistlib.load(f).get('MCMMetadataIdentifier', '')
        if identifier == 'com.apple.FileProvider.LocalStorage':
            roots.append((identifier, folder))
daemon = data / 'Library/Application Support/FileProvider'
if daemon.exists():
    roots.append(('com.apple.fileproviderd fresh simulator metadata', daemon))
for identifier, folder in roots:
    for db in folder.rglob('*'):
        if not db.is_file() or db.stat().st_size > 32 * 1024 * 1024:
            continue
        with db.open('rb') as f:
            if f.read(16) != b'SQLite format 3\0':
                continue
        # Keep raw scratch copies outside the upload tree even if collection is interrupted.
        target = out.parent / 'provider-db-snapshots' / str(len(records))
        target.mkdir(parents=True, exist_ok=True)
        for suffix in ['', '-wal', '-shm']:
            source = Path(str(db) + suffix)
            if source.exists():
                shutil.copyfile(source, target / ('snapshot.sqlite' + suffix))
        record = dict(group_identifier=identifier, relative_path=str(db.relative_to(folder)), tables=[])
        try:
            connection = sqlite3.connect('file:' + str(target / 'snapshot.sqlite') + '?mode=ro', uri=True)
            for name, sql in connection.execute("SELECT name,sql FROM sqlite_master WHERE type='table'"):
                quoted = '"' + name.replace('"', '""') + '"'
                columns = list(connection.execute('PRAGMA table_info(' + quoted + ')'))
                # Show schema and scalar path/name/id metadata only; omit blobs/bookmarks/tokens.
                selected = [c[1] for c in columns if (c[1].lower() in ['id', 'rowid', 'docid', 'documentid', 'document_id', 'st_ino', 'st_dev']
                            or any(s in c[1].lower() for s in ['name', 'path', 'identifier', 'itemid', 'fileid', 'parentid']))
                            and not any(s in c[1].lower() for s in ['token', 'bookmark', 'secret', 'key', 'credential'])]
                table = dict(name=name, schema=sql, columns=[dict(name=c[1], type=c[2]) for c in columns])
                if selected:
                    fields = ','.join('"' + s.replace('"', '""') + '"' for s in selected)
                    rows = connection.execute('SELECT ' + fields + ' FROM ' + quoted + ' LIMIT 100').fetchall()
                    table['synthetic_file_metadata'] = [dict(zip(selected, [v if isinstance(v, (str, int, float, type(None))) else '<binary omitted>' for v in row])) for row in rows]
                record['tables'].append(table)
            connection.close()
        except Exception as error:
            record['error'] = str(error)
        records.append(record)
(out / 'provider-databases.json').write_text(json.dumps(records, indent=2) + '\n')
# Raw SQLite snapshots are diagnostic inputs only; do not upload any blob/bookmark data.
shutil.rmtree(out.parent / 'provider-db-snapshots', ignore_errors=True)
print('Reviewed', len(records), 'read-only provider database snapshots')
