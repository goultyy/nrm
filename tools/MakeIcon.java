import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Draws the application icon (a green rounded square with a white N) and writes the PNG sizes the
 * app window uses plus packaging/app.ico for the installer. Run from the project folder:
 * {@code java tools/MakeIcon.java}
 */
public class MakeIcon {

    static BufferedImage draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        double pad = size * 0.02;
        RoundRectangle2D shape = new RoundRectangle2D.Double(pad, pad, size - 2 * pad, size - 2 * pad, size * 0.22, size * 0.22);
        g.setPaint(new GradientPaint(0, 0, new Color(0x0b6e4f), size, size, new Color(0x22b573)));
        g.fill(shape);
        // Two thin "server" lines under the letter.
        g.setColor(new Color(255, 255, 255, 110));
        int barHeight = Math.max(1, size / 32);
        g.fillRoundRect((int) (size * 0.26), (int) (size * 0.80), (int) (size * 0.48), barHeight, barHeight, barHeight);
        g.fillRoundRect((int) (size * 0.26), (int) (size * 0.86), (int) (size * 0.32), barHeight, barHeight, barHeight);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) (size * 0.62)));
        FontMetrics fm = g.getFontMetrics();
        String letter = "N";
        int x = (size - fm.stringWidth(letter)) / 2;
        int y = (int) (size * 0.13) + fm.getAscent() - (int) (size * 0.02);
        g.drawString(letter, x, y);
        g.dispose();
        return img;
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        Path resources = Path.of("src", "main", "resources", "mt", "su", "nrm", "app");
        Files.createDirectories(resources);
        int[] appSizes = {16, 24, 32, 48, 64, 128, 256};
        byte[][] pngs = new byte[appSizes.length][];
        for (int i = 0; i < appSizes.length; i++) {
            pngs[i] = png(draw(appSizes[i]));
            Files.write(resources.resolve("icon-" + appSizes[i] + ".png"), pngs[i]);
        }
        // ICO with PNG-compressed images (supported since Windows Vista).
        Path ico = Path.of("packaging", "app.ico");
        Files.createDirectories(ico.getParent());
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(ico))) {
            out.write(le16(0));
            out.write(le16(1));
            out.write(le16(appSizes.length));
            int offset = 6 + 16 * appSizes.length;
            for (int i = 0; i < appSizes.length; i++) {
                int s = appSizes[i] >= 256 ? 0 : appSizes[i];
                out.writeByte(s);
                out.writeByte(s);
                out.writeByte(0);
                out.writeByte(0);
                out.write(le16(1));
                out.write(le16(32));
                out.write(le32(pngs[i].length));
                out.write(le32(offset));
                offset += pngs[i].length;
            }
            for (byte[] p : pngs) {
                out.write(p);
            }
        }
        System.out.println("Wrote icons to " + resources + " and " + ico);
    }

    static byte[] le16(int v) {
        return new byte[] {(byte) v, (byte) (v >> 8)};
    }

    static byte[] le32(int v) {
        return new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }
}
