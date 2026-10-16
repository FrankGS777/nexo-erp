package dev.nexo.erp.security;

import static org.assertj.core.api.Assertions.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class MfaQrCodeTests {
    @Test void qrRoundTripsTheExactAuthenticatorConfiguration() throws Exception {
        String uri = new TotpService().provisioningUri("Nexo ERP", "demo@example.invalid",
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        String data = new MfaQrCode().dataUri(uri);
        assertThat(data).startsWith("data:image/png;base64,");
        var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(data.substring(data.indexOf(',')+1))));
        assertThat(image.getWidth()).isEqualTo(288);
        int[] pixels = image.getRGB(0,0,288,288,null,0,288);
        var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(288,288,pixels)));
        assertThat(new MultiFormatReader().decode(bitmap).getText()).isEqualTo(uri);
    }
    @Test void rejectsNonAuthenticatorPayloads() {
        assertThatThrownBy(() -> new MfaQrCode().dataUri("https://example.com"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
