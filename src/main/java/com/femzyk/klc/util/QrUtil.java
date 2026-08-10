package com.femzyk.klc.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.nio.file.FileSystems;
import java.nio.file.Path;

public class QrUtil {
    public static String makeQr(String text, String outPath, int size) throws Exception {
        QRCodeWriter w = new QRCodeWriter();
        BitMatrix bm = w.encode(text, BarcodeFormat.QR_CODE, size, size);
        Path p = FileSystems.getDefault().getPath(outPath);
        MatrixToImageWriter.writeToPath(bm, "PNG", p);
        return outPath;
    }
}
