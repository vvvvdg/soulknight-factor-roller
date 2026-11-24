package com.yqqs.factor;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.HashMap;

/** 一次性读入 icons.dat（单文件资源，避免逐个 asset 打开失败） */
public class IconPack {

    private static final HashMap<String, byte[]> DATA = new HashMap<>();
    private static boolean loaded = false;
    private static String err = "";

    public static synchronized void ensure(AssetManager am) {
        if (loaded) return;
        try (InputStream in = am.open("icons.dat");
             DataInputStream di = new DataInputStream(in)) {
            int n = di.readInt();
            for (int i = 0; i < n; i++) {
                int il = di.readInt();
                byte[] ib = new byte[il];
                di.readFully(ib);
                String id = new String(ib, "UTF-8");
                int nl = di.readInt();
                byte[] nb = new byte[nl];
                di.readFully(nb);
                String name = new String(nb, "UTF-8");
                int dl = di.readInt();
                byte[] db = new byte[dl];
                di.readFully(db);
                DATA.put(name, db);
                DATA.put("id:" + id, db);
            }
            loaded = true;
        } catch (Throwable t) {
            err = String.valueOf(t);
        }
    }

    public static String error() { return err; }

    public static int count() { return DATA.size() / 2; }

    public static Bitmap decode(String name) {
        byte[] d = DATA.get(name);
        if (d == null) return null;
        return BitmapFactory.decodeByteArray(d, 0, d.length);
    }
}