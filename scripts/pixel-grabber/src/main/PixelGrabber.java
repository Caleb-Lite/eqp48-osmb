package main;

import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.visual.color.HSLPalette;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

@ScriptDefinition(
        author = "eqp48",
        name = "Pixel Grabber",
        description = "Build your searchable pixels with ease.",
        skillCategory = SkillCategory.OTHER,
        version = 1.0
)
public class PixelGrabber extends Script {
    private static final long CAPTURE_INTERVAL_MS = 200;
    private static final String WINDOW_TITLE = "Pixel Grabber";

    private JFrame frame;
    private ScreenImagePanel imagePanel;
    private MagnifierPanel magnifierPanel;
    private JComboBox<ColorMode> colorModeSelector;
    private JTextArea outputArea;
    private final List<Integer> clickedPixels = new ArrayList<>();
    private long lastCaptureMs = 0L;
    private ColorMode selectedColorMode = ColorMode.HSL;
    private BufferedImage snapshotImage;
    private boolean snapshotRequested = true;

    public PixelGrabber(Object scriptCore) {
        super(scriptCore);
    }

    @Override
    public void onStart() {
        SwingUtilities.invokeLater(() -> {
            BufferedImage initialImage = captureScreenImage();
            if (initialImage == null) {
                initialImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            }
            imagePanel = new ScreenImagePanel(initialImage);
            magnifierPanel = new MagnifierPanel(16, 8);
            colorModeSelector = buildColorModeSelector();
            outputArea = buildOutputArea();
            imagePanel.setHoverListener(this::updateHoverInfo);
            imagePanel.setHoverExitListener(this::clearHoverInfo);
            imagePanel.setClickListener(this::recordPixelClick);
            snapshotImage = initialImage;

            frame = new JFrame(WINDOW_TITLE);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setLayout(new BorderLayout());
            frame.add(buildMainPanel(), BorderLayout.CENTER);
            frame.add(buildOutputPanel(), BorderLayout.SOUTH);
            frame.pack();
            frame.setVisible(true);
        });
    }

    @Override
    public int poll() {
        if (frame == null || imagePanel == null || !frame.isDisplayable()) {
            return 200;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - lastCaptureMs;
        if (!snapshotRequested) {
            return (int) CAPTURE_INTERVAL_MS;
        }
        if (elapsed < CAPTURE_INTERVAL_MS) {
            return (int) (CAPTURE_INTERVAL_MS - elapsed);
        }

        BufferedImage image = captureScreenImage();
        snapshotRequested = false;
        if (image == null) {
            lastCaptureMs = now;
            return (int) CAPTURE_INTERVAL_MS;
        }
        snapshotImage = image;
        lastCaptureMs = now;

        SwingUtilities.invokeLater(() -> applyImageUpdate(image));
        return (int) CAPTURE_INTERVAL_MS;
    }

    @Override
    public void stop() {
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.dispose();
                frame = null;
                imagePanel = null;
                magnifierPanel = null;
                outputArea = null;
            }
        });
        super.stop();
    }

    private BufferedImage captureScreenImage() {
        try {
            return getScreen().getImage().toBufferedImage();
        } catch (Exception e) {
            return null;
        }
    }

    private JPanel buildMainPanel() {
        JScrollPane scrollPane = new JScrollPane(imagePanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        JPanel magnifierContainer = new JPanel(new GridBagLayout());
        magnifierContainer.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.anchor = GridBagConstraints.CENTER;
        magnifierContainer.add(magnifierPanel, gbc);
        Dimension magnifierSize = magnifierPanel.getPreferredSize();
        magnifierContainer.setPreferredSize(new Dimension(magnifierSize.width + 12, magnifierSize.height + 12));

        JPanel content = new JPanel(new BorderLayout());
        content.add(scrollPane, BorderLayout.CENTER);
        content.add(magnifierContainer, BorderLayout.EAST);
        return content;
    }

    private JPanel buildOutputPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        panel.add(buildOutputHeader(), BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(outputArea);
        scrollPane.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildOutputHeader() {
        JPanel header = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new BorderLayout());
        JLabel label = new JLabel("Color model:");
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        left.add(label, BorderLayout.WEST);
        left.add(colorModeSelector, BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout());
        right.add(buildOutputActions(), BorderLayout.EAST);

        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        return header;
    }

    private JPanel buildOutputActions() {
        JPanel panel = new JPanel(new BorderLayout());
        javax.swing.JButton snapshotButton = new javax.swing.JButton("Take Snapshot");
        snapshotButton.addActionListener(e -> requestSnapshot());
        javax.swing.JButton copyButton = new javax.swing.JButton("Copy");
        copyButton.addActionListener(e -> copyOutput());
        javax.swing.JButton clearButton = new javax.swing.JButton("Clear");
        clearButton.addActionListener(e -> clearOutput());
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(copyButton, BorderLayout.WEST);
        buttons.add(clearButton, BorderLayout.EAST);
        panel.add(snapshotButton, BorderLayout.WEST);
        panel.add(buttons, BorderLayout.EAST);
        return panel;
    }

    private JComboBox<ColorMode> buildColorModeSelector() {
        JComboBox<ColorMode> comboBox = new JComboBox<>(ColorMode.values());
        comboBox.setSelectedItem(ColorMode.HSL);
        comboBox.addActionListener(e -> {
            Object selection = comboBox.getSelectedItem();
            if (selection instanceof ColorMode mode) {
                selectedColorMode = mode;
                updateOutputArea();
            }
        });
        return comboBox;
    }

    private JTextArea buildOutputArea() {
        JTextArea area = new JTextArea(6, 60);
        area.setEditable(false);
        area.setFont(new Font("Monospaced", Font.PLAIN, 12));
        area.setLineWrap(false);
        area.setText("Click the image to add SearchablePixel entries.");
        return area;
    }

    private void applyImageUpdate(BufferedImage image) {
        if (imagePanel == null) {
            return;
        }
        imagePanel.setImage(image);
        Point lastHover = imagePanel.getLastHoverPoint();
        if (lastHover != null) {
            updateHoverInfo(lastHover.x, lastHover.y);
        }
    }

    private void updateHoverInfo(int x, int y) {
        if (imagePanel == null || magnifierPanel == null) {
            return;
        }
        BufferedImage image = snapshotImage != null ? snapshotImage : imagePanel.getImage();
        if (image == null) {
            return;
        }
        if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) {
            magnifierPanel.update(image, x, y);
            return;
        }

        int argb = image.getRGB(x, y);
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        String hex = String.format("#%02X%02X%02X", r, g, b);
        double[] hsl = HSLPalette.rgbToHsl(argb);
        String text = String.format(
                "x=%d y=%d  rgb=(%d,%d,%d)  hex=%s  argb=%d  hsl=(%.2f, %.2f, %.2f)",
                x,
                y,
                r,
                g,
                b,
                hex,
                argb,
                hsl[0],
                hsl[1],
                hsl[2]
        );
        magnifierPanel.update(image, x, y);
    }

    private void clearHoverInfo() {
    }

    private void recordPixelClick(int x, int y) {
        if (imagePanel == null || outputArea == null) {
            return;
        }
        BufferedImage image = snapshotImage != null ? snapshotImage : imagePanel.getImage();
        if (image == null) {
            return;
        }
        if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) {
            return;
        }
        int argb = image.getRGB(x, y);
        clickedPixels.add(argb);
        updateOutputArea();
    }

    private void updateOutputArea() {
        if (outputArea == null) {
            return;
        }
        if (clickedPixels.isEmpty()) {
            outputArea.setText("Click the image to add SearchablePixel entries.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("private static final SearchablePixel[] PIXEL_CLUSTER = new SearchablePixel[] {\n");
        for (Integer rgb : clickedPixels) {
            sb.append("    new SearchablePixel(")
                    .append(rgb)
                    .append(", new SingleThresholdComparator(0), ColorModel.")
                    .append(selectedColorMode.name())
                    .append("),\n");
        }
        sb.append("};");
        outputArea.setText(sb.toString());
        outputArea.setCaretPosition(0);
    }

    private void clearOutput() {
        clickedPixels.clear();
        updateOutputArea();
    }

    private void requestSnapshot() {
        snapshotRequested = true;
    }

    private void copyOutput() {
        if (outputArea == null) {
            return;
        }
        String text = outputArea.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        java.awt.datatransfer.StringSelection selection = new java.awt.datatransfer.StringSelection(text);
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
    }

    private enum ColorMode {
        HSL,
        RGB
    }

    private static final class ScreenImagePanel extends JPanel {
        private BufferedImage image;
        private HoverListener hoverListener;
        private Runnable hoverExitListener;
        private ClickListener clickListener;
        private Point lastHoverPoint;

        private ScreenImagePanel(BufferedImage image) {
            setImage(image);
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    handleHover(e.getX(), e.getY());
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    handleHover(e.getX(), e.getY());
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (clickListener != null) {
                        clickListener.onClick(e.getX(), e.getY());
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    lastHoverPoint = null;
                    if (hoverExitListener != null) {
                        hoverExitListener.run();
                    }
                }
            });
        }

        private void handleHover(int x, int y) {
            lastHoverPoint = new Point(x, y);
            if (hoverListener != null) {
                hoverListener.onHover(x, y);
            }
        }

        private void setHoverListener(HoverListener listener) {
            this.hoverListener = listener;
        }

        private void setHoverExitListener(Runnable listener) {
            this.hoverExitListener = listener;
        }

        private void setClickListener(ClickListener listener) {
            this.clickListener = listener;
        }

        private Point getLastHoverPoint() {
            return lastHoverPoint;
        }

        private BufferedImage getImage() {
            return image;
        }

        private void setImage(BufferedImage image) {
            this.image = image;
            if (image != null) {
                setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));
            }
            revalidate();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image != null) {
                g.drawImage(image, 0, 0, null);
            }
        }
    }

    private interface HoverListener {
        void onHover(int x, int y);
    }

    private interface ClickListener {
        void onClick(int x, int y);
    }

    

    private static final class MagnifierPanel extends JPanel {
        private final int sourceSize;
        private final int zoomFactor;
        private BufferedImage image;
        private int centerX;
        private int centerY;

        private MagnifierPanel(int sourceSize, int zoomFactor) {
            this.sourceSize = sourceSize;
            this.zoomFactor = zoomFactor;
            int size = sourceSize * zoomFactor;
            setPreferredSize(new Dimension(size, size));
            setMinimumSize(new Dimension(size, size));
            setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        }

        private void update(BufferedImage image, int x, int y) {
            this.image = image;
            this.centerX = x;
            this.centerY = y;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int half = sourceSize / 2;
            for (int sx = 0; sx < sourceSize; sx++) {
                for (int sy = 0; sy < sourceSize; sy++) {
                    int srcX = centerX - half + sx;
                    int srcY = centerY - half + sy;
                    Color color = Color.BLACK;
                    if (image != null && srcX >= 0 && srcY >= 0 && srcX < image.getWidth() && srcY < image.getHeight()) {
                        color = new Color(image.getRGB(srcX, srcY), true);
                    }
                    g.setColor(color);
                    g.fillRect(sx * zoomFactor, sy * zoomFactor, zoomFactor, zoomFactor);
                }
            }

            int centerIndex = sourceSize / 2;
            g.setColor(Color.RED);
            g.drawRect(centerIndex * zoomFactor, centerIndex * zoomFactor, zoomFactor, zoomFactor);
        }
    }
}
