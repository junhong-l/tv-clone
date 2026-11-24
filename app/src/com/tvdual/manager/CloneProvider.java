package com.tvdual.manager;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把 cacheDir 里的克隆包以 content:// 形式交给系统安装器。
 * 不依赖 androidx 的 FileProvider（本项目不引任何依赖库）。
 * provider 为 exported=false，只有拿到临时授权的安装器能读。
 */
public class CloneProvider extends ContentProvider {

    static final String AUTHORITY = "com.tvdual.manager.files";

    static Uri uriFor(File apk) {
        return Uri.parse("content://" + AUTHORITY + "/" + apk.getName());
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException("非法路径: " + uri);
        }
        File f = new File(getContext().getCacheDir(), name);
        if (!f.exists() || f.length() == 0) throw new FileNotFoundException(name);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
