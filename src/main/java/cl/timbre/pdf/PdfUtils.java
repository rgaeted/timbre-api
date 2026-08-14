package cl.timbre.pdf;

import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;

public final class PdfUtils {
    private PdfUtils() {}

    public static Font boldFont(int size) {
        Font font = FontFactory.getFont(FontFactory.HELVETICA_BOLD, size);
        font.setColor(0, 0, 0);
        return font;
    }

    public static Font normalFont(int size) {
        Font font = FontFactory.getFont(FontFactory.HELVETICA, size);
        font.setColor(0, 0, 0);
        return font;
    }

    public static String formatMoney(int cents) {
        // Convert cents to CLP string: 119000 cents = $119.000
        long pesos = cents;
        return String.format("$%,d", pesos).replace(",", ".");
    }
}
