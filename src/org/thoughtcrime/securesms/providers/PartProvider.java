/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.providers;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore.MediaColumns;
import android.support.annotation.NonNull;
import android.util.Log;
import android.webkit.MimeTypeMap;

import org.thoughtcrime.securesms.attachments.AttachmentId;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.mms.PartUriParser;
import org.thoughtcrime.securesms.service.KeyCachingService;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class PartProvider extends ContentProvider {
  private static final String TAG = PartProvider.class.getSimpleName();

  private static final String CONTENT_URI_STRING        = "content://org.thoughtcrime.provider.securesms/part";
  private static final Uri    CONTENT_URI               = Uri.parse(CONTENT_URI_STRING);
  private static final int    SINGLE_ROW                = 1;
  private static final int    SINGLE_ROW_WITH_EXTENSION = 2;

  private static final UriMatcher uriMatcher;

  static {
    uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    uriMatcher.addURI("org.thoughtcrime.provider.securesms", "part/*/#", SINGLE_ROW);
    uriMatcher.addURI("org.thoughtcrime.provider.securesms", "part/*/#/*", SINGLE_ROW_WITH_EXTENSION);
  }

  @Override
  public boolean onCreate() {
    Log.w(TAG, "onCreate()");
    return true;
  }

  public static Uri getContentUri(AttachmentId attachmentId) {
    Uri uri = Uri.withAppendedPath(CONTENT_URI, String.valueOf(attachmentId.getUniqueId()));
    return ContentUris.withAppendedId(uri, attachmentId.getRowId());
  }

  public static Uri getContentUriWithExtension(AttachmentId attachmentId, String mimeType) {
    final String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
    final String filename  = "attachment." + (extension != null ? extension : "jpg");
    return getContentUri(attachmentId).buildUpon().appendEncodedPath(filename).build();
  }

  @SuppressWarnings("ConstantConditions")
  private File copyPartToTemporaryFile(MasterSecret masterSecret, AttachmentId attachmentId) throws IOException {
    InputStream in        = DatabaseFactory.getAttachmentDatabase(getContext()).getAttachmentStream(masterSecret, attachmentId);
    File tmpDir           = getContext().getDir("tmp", 0);
    File tmpFile          = File.createTempFile("test", ".jpg", tmpDir);
    FileOutputStream fout = new FileOutputStream(tmpFile);

    byte[] buffer         = new byte[512];
    int read;

    while ((read = in.read(buffer)) != -1)
      fout.write(buffer, 0, read);

    in.close();

    return tmpFile;
  }

  @Override
  public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
    MasterSecret masterSecret = KeyCachingService.getMasterSecret(getContext());
    Log.w(TAG, "openFile() called!");

    if (masterSecret == null) {
      Log.w(TAG, "masterSecret was null, abandoning.");
      return null;
    }

    switch (uriMatcher.match(uri)) {
    case SINGLE_ROW:
    case SINGLE_ROW_WITH_EXTENSION:
      Log.w(TAG, "Parting out a single row...");
      try {
        PartUriParser        partUri = new PartUriParser(uri);
        File                 tmpFile = copyPartToTemporaryFile(masterSecret, partUri.getPartId());
        ParcelFileDescriptor pdf     = ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_ONLY);

        if (!tmpFile.delete()) {
          Log.w(TAG, "Failed to delete temp file.");
        }

        return pdf;
      } catch (IOException ioe) {
        Log.w(TAG, ioe);
        throw new FileNotFoundException("Error opening file");
      }
    }

    throw new FileNotFoundException("Request for bad part.");
  }

  @Override
  public int delete(@NonNull Uri arg0, String arg1, String[] arg2) {
    return 0;
  }

  @Override
  public String getType(@NonNull Uri uri) {
    switch (uriMatcher.match(uri)) {
      case SINGLE_ROW:
      case SINGLE_ROW_WITH_EXTENSION:
        PartUriParser      partUri    = new PartUriParser(uri);
        AttachmentDatabase database   = DatabaseFactory.getAttachmentDatabase(getContext());
        DatabaseAttachment attachment = database.getAttachment(partUri.getPartId());
        if (attachment != null) return attachment.getContentType();
    }
    return null;
  }

  @Override
  public Uri insert(@NonNull Uri arg0, ContentValues arg1) {
    return null;
  }

  @Override
  public Cursor query(@NonNull Uri uri, String[] projection, String selection,
                      String[] selectionArgs, String sortOrder)
  {
    if (projection == null) {
      projection = new String[] {MediaColumns._ID, MediaColumns.DATA};
    }

    PartUriParser partUri = new PartUriParser(uri);
    MatrixCursor  cursor  = new MatrixCursor(projection);
    Object[]      row     = new Object[projection.length];

    for (int i = 0; i < row.length; i++) {
      switch (projection[i]) {
        case MediaColumns._ID:  row[i] = partUri.getPartId().getRowId(); break;
        case MediaColumns.DATA: row[i] = uri.toString();                 break;
      }
    }

    cursor.addRow(row);
    return cursor;
  }

  @Override
  public int update(@NonNull Uri arg0, ContentValues arg1, String arg2, String[] arg3) {
    return 0;
  }
}
