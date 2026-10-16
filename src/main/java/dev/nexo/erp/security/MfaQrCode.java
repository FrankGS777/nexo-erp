package dev.nexo.erp.security;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
public class MfaQrCode {
    public String dataUri(String provisioningUri) {
        if (provisioningUri == null || !provisioningUri.startsWith("otpauth://totp/")) {
            throw new IllegalArgumentException("Configuracion del autenticador no valida");
        }
        try {
            var bits = new QRCodeWriter().encode(provisioningUri, BarcodeFormat.QR_CODE,
                288, 288, Map.of(EncodeHintType.MARGIN, 4, EncodeHintType.CHARACTER_SET, "UTF-8"));
            var image = new BufferedImage(288, 288, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 288; y++) {
                for (int x = 0; x < 288; x++) image.setRGB(x, y, bits.get(x, y) ? 0 : 0xffffff);
            }
            var output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo generar el QR del autenticador");
        }
    }
}
