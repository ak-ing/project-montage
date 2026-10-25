// SPDX-License-Identifier: Apache-2.0
// Read-only comparison of host and Simulator Foundation on the same synthetic files.
#import <Foundation/Foundation.h>
#include <stdio.h>
#include <sys/attr.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/fsgetpath.h>
#include <errno.h>
#include <string.h>
#include <limits.h>

static id value(id object) { return object ?: [NSNull null]; }

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        NSMutableArray *records = [NSMutableArray array];
        for (int i = 1; i < argc; i++) {
            NSString *path = [NSString stringWithUTF8String:argv[i]];
            NSURL *url = [NSURL fileURLWithPath:path];
            NSError *error = nil;
            NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:path error:&error];
            NSMutableDictionary *record = [@{@"path":path, @"attributes":value(attributes), @"attributes_error":value(error.description)} mutableCopy];
            if (attributes && [attributes[NSFileSize] unsignedLongLongValue] <= 65536) {
                struct stat status;
                struct statfs volume;
                if (stat(path.fileSystemRepresentation, &status) == 0 && statfs(path.fileSystemRepresentation, &volume) == 0) {
                    record[@"filesystem"] = @{@"type":[NSString stringWithUTF8String:volume.f_fstypename],
                        @"mount":[NSString stringWithUTF8String:volume.f_mntonname],
                        @"source":[NSString stringWithUTF8String:volume.f_mntfromname], @"fsid":@[@(volume.f_fsid.val[0]), @(volume.f_fsid.val[1])],
                        @"stat_device":@(status.st_dev), @"inode":@(status.st_ino), @"bsd_flags":@(status.st_flags)};
                    NSMutableArray *resolutions = [NSMutableArray array];
                    fsid_t ids[] = {volume.f_fsid, {{status.st_dev, 0}}};
                    for (int j = 0; j < 2; j++) {
                        char resolved[PATH_MAX] = {0};
                        errno = 0;
                        ssize_t count = fsgetpath(resolved, sizeof(resolved), &ids[j], status.st_ino);
                        int failure = errno;
                        [resolutions addObject:@{@"fsid":@[@(ids[j].val[0]), @(ids[j].val[1])],
                            @"path":count > 0 ? [NSString stringWithUTF8String:resolved] : (id)[NSNull null],
                            @"errno":@(failure), @"error":count > 0 ? (id)[NSNull null] : [NSString stringWithUTF8String:strerror(failure)]}];
                    }
                    record[@"inode_path_resolutions"] = resolutions;
                }
                struct attrlist request = {0};
                request.bitmapcount = ATTR_BIT_MAP_COUNT;
                request.commonattr = ATTR_CMN_DOCUMENT_ID;
                struct { uint32_t length; uint32_t document; } documentResult = {0};
                errno = 0;
                int documentStatus = getattrlist(path.fileSystemRepresentation, &request, &documentResult, sizeof(documentResult), 0);
                record[@"kernel_document_id"] = documentStatus == 0 ? @(documentResult.document) : (id)[NSNull null];
                record[@"kernel_document_id_errno"] = @(documentStatus == 0 ? 0 : errno);
                error = nil;
                NSData *direct = [NSData dataWithContentsOfURL:url options:0 error:&error];
                record[@"direct_bytes"] = direct ? @(direct.length) : [NSNull null];
                record[@"direct_error"] = value(error.description);
                NSURL *reference = [url fileReferenceURL];
                record[@"reference_url"] = value(reference.absoluteString);
                record[@"resolved_path"] = value(reference.filePathURL.path);
                error = nil;
                NSData *indirect = reference ? [NSData dataWithContentsOfURL:reference options:0 error:&error] : nil;
                record[@"reference_bytes"] = indirect ? @(indirect.length) : [NSNull null];
                record[@"reference_error"] = value(error.description);
                id identifier = nil;
                [url getResourceValue:&identifier forKey:NSURLFileResourceIdentifierKey error:nil];
                record[@"resource_identifier"] = value([identifier description]);
                id document = nil;
                [url getResourceValue:&document forKey:NSURLDocumentIdentifierKey error:nil];
                record[@"document_identifier"] = value(document);
                id generation = nil;
                [url getResourceValue:&generation forKey:NSURLGenerationIdentifierKey error:nil];
                record[@"generation_identifier"] = value([generation description]);
            }
            // NSDate / opaque attributes aren't JSON values. Keep a bounded textual description.
            record[@"attributes"] = value([attributes description]);
            [records addObject:record];
        }
        NSDictionary *result = @{@"home":NSHomeDirectory(), @"files":records};
        NSData *json = [NSJSONSerialization dataWithJSONObject:result options:NSJSONWritingPrettyPrinted | NSJSONWritingSortedKeys error:nil];
        fwrite(json.bytes, 1, json.length, stdout);
        putchar('\n');
    }
    return 0;
}
