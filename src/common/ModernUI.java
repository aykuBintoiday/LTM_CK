package common;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Shared visual theme for Smart Classroom V3.11.
 * Pure Swing/JDK 17: no external UI dependency is required.
 */
public final class ModernUI {
    private ModernUI() {}

    public static final Color NAVY = new Color(15, 23, 42);
    public static final Color NAVY_2 = new Color(30, 41, 59);
    public static final Color BLUE = new Color(37, 99, 235);
    public static final Color BLUE_HOVER = new Color(29, 78, 216);
    public static final Color BLUE_SOFT = new Color(239, 246, 255);
    public static final Color CYAN = new Color(14, 165, 233);
    public static final Color BG = new Color(246, 248, 252);
    public static final Color CARD = Color.WHITE;
    public static final Color BORDER = new Color(226, 232, 240);
    public static final Color TEXT = new Color(15, 23, 42);
    public static final Color MUTED = new Color(100, 116, 139);
    public static final Color GREEN = new Color(22, 163, 74);
    public static final Color GREEN_SOFT = new Color(240, 253, 244);
    public static final Color RED = new Color(220, 38, 38);
    public static final Color RED_HOVER = new Color(185, 28, 28);
    public static final Color AMBER = new Color(217, 119, 6);
    public static final Color DARK_SURFACE = new Color(17, 24, 39);
    public static final Color DARK_SURFACE_2 = new Color(31, 41, 55);
    public static final Color DARK_BORDER = new Color(55, 65, 81);

    public static final Font FONT = new Font("Segoe UI", Font.PLAIN, 14);
    public static final Font FONT_MEDIUM = new Font("Segoe UI", Font.BOLD, 14);
    public static final Font FONT_TITLE = new Font("Segoe UI", Font.BOLD, 26);
    public static final int INPUT_HEIGHT = 46;

    public enum ButtonKind { PRIMARY, SECONDARY, GHOST, DANGER, SUCCESS, DARK }

    public static void installGlobalDefaults() {
        UIManager.put("Label.font", FONT);
        UIManager.put("Button.font", FONT_MEDIUM);
        UIManager.put("TextField.font", FONT);
        UIManager.put("PasswordField.font", FONT);
        UIManager.put("ComboBox.font", FONT);
        UIManager.put("Table.font", FONT);
        UIManager.put("TableHeader.font", FONT_MEDIUM);
        UIManager.put("TabbedPane.font", FONT_MEDIUM);
        UIManager.put("OptionPane.messageFont", FONT);
        UIManager.put("OptionPane.buttonFont", FONT_MEDIUM);
        UIManager.put("ToolTip.font", new Font("Segoe UI", Font.PLAIN, 12));
        UIManager.put("Panel.background", BG);
        UIManager.put("Table.gridColor", BORDER);
        UIManager.put("Table.selectionBackground", new Color(219, 234, 254));
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("ScrollPane.border", new EmptyBorder(0, 0, 0, 0));
    }

    public static JLabel title(String text, int size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.BOLD, size));
        l.setForeground(color);
        return l;
    }

    public static JLabel subtitle(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        l.setForeground(MUTED);
        return l;
    }

    public static void styleInput(JComponent input) {
        input.setFont(FONT);
        input.setForeground(TEXT);
        input.setBackground(Color.WHITE);

        // Keep enough vertical room for Segoe UI at Windows 125%/150% scaling.
        // The previous border contributed 28px of top/bottom insets and some
        // screens forced the field down to 36px, which clipped the glyphs.
        if (input instanceof JTextField field) {
            field.setBorder(BorderFactory.createCompoundBorder(
                    new RoundedBorder(BORDER, 12, 1),
                    new EmptyBorder(7, 12, 7, 12)));
            field.setMargin(new Insets(0, 0, 0, 0));
            field.setCaretColor(BLUE);
            field.setSelectionColor(new Color(191, 219, 254));
        } else if (input instanceof JComboBox<?> combo) {
            combo.setBorder(BorderFactory.createCompoundBorder(
                    new RoundedBorder(BORDER, 12, 1),
                    new EmptyBorder(3, 8, 3, 8)));
            combo.setBackground(Color.WHITE);
        } else {
            input.setBorder(BorderFactory.createCompoundBorder(
                    new RoundedBorder(BORDER, 12, 1),
                    new EmptyBorder(7, 12, 7, 12)));
        }

        Dimension pref = input.getPreferredSize();
        int width = Math.max(120, pref == null ? 120 : pref.width);
        input.setPreferredSize(new Dimension(width, INPUT_HEIGHT));
        input.setMinimumSize(new Dimension(Math.min(width, 120), INPUT_HEIGHT));
    }

    public static void styleButton(JButton b, ButtonKind kind) {
        Color base;
        Color hover;
        Color fg;
        switch (kind) {
            case PRIMARY -> { base = BLUE; hover = BLUE_HOVER; fg = Color.WHITE; }
            case DANGER -> { base = RED; hover = RED_HOVER; fg = Color.WHITE; }
            case SUCCESS -> { base = GREEN; hover = new Color(21, 128, 61); fg = Color.WHITE; }
            case DARK -> { base = DARK_SURFACE_2; hover = new Color(55, 65, 81); fg = Color.WHITE; }
            case SECONDARY -> { base = new Color(241, 245, 249); hover = new Color(226, 232, 240); fg = TEXT; }
            default -> { base = Color.WHITE; hover = new Color(248, 250, 252); fg = BLUE; }
        }
        b.setUI(new RoundedButtonUI(base, hover, 14));
        b.setForeground(fg);
        b.setFont(FONT_MEDIUM);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(new EmptyBorder(10, 16, 10, 16));
    }

    public static void styleCompactButton(JButton b, ButtonKind kind) {
        styleButton(b, kind);
        b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setBorder(new EmptyBorder(8, 12, 8, 12));
    }

    public static void styleTable(JTable table) {
        table.setRowHeight(38);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setGridColor(new Color(241, 245, 249));
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setSelectionBackground(new Color(219, 234, 254));
        table.setSelectionForeground(TEXT);
        table.setForeground(TEXT);
        table.setBackground(Color.WHITE);
        table.setFont(FONT);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JTableHeader h = table.getTableHeader();
        h.setFont(new Font("Segoe UI", Font.BOLD, 12));
        h.setForeground(MUTED);
        h.setBackground(new Color(248, 250, 252));
        h.setPreferredSize(new Dimension(h.getPreferredSize().width, 38));
        h.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
    }

    public static void styleTabs(JTabbedPane tabs) {
        tabs.setFont(FONT_MEDIUM);
        tabs.setForeground(TEXT);
        tabs.setBackground(Color.WHITE);
        tabs.setOpaque(false);
        tabs.setBorder(new EmptyBorder(0, 0, 0, 0));
        tabs.setUI(new ModernTabbedPaneUI());
    }

    public static JScrollPane scroll(Component view) {
        JScrollPane sp = new JScrollPane(view);
        sp.setBorder(new RoundedBorder(BORDER, 14, 1));
        sp.getViewport().setBackground(Color.WHITE);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        sp.getHorizontalScrollBar().setUnitIncrement(18);
        return sp;
    }

    public static JPanel pill(String text, Color bg, Color fg) {
        CardPanel p = new CardPanel(999, bg, bg, false);
        p.setLayout(new BorderLayout());
        p.setBorder(new EmptyBorder(6, 12, 6, 12));
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.BOLD, 12));
        l.setForeground(fg);
        p.add(l);
        return p;
    }

    public static class GradientPanel extends JPanel {
        private final Color c1, c2;
        private final int radius;
        public GradientPanel(Color c1, Color c2, int radius) {
            this.c1 = c1; this.c2 = c2; this.radius = radius;
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            GradientPaint gp = new GradientPaint(0, 0, c1, getWidth(), getHeight(), c2);
            g2.setPaint(gp);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    public static class CardPanel extends JPanel {
        private final int radius;
        private final Color fill;
        private final Color border;
        private final boolean shadow;
        public CardPanel() { this(18, CARD, BORDER, true); }
        public CardPanel(int radius, Color fill, Color border, boolean shadow) {
            this.radius = radius; this.fill = fill; this.border = border; this.shadow = shadow;
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (shadow) {
                g2.setColor(new Color(15, 23, 42, 18));
                g2.fillRoundRect(2, 4, Math.max(0, getWidth()-4), Math.max(0, getHeight()-6), radius, radius);
            }
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, Math.max(0, getWidth()-2), Math.max(0, getHeight()-3), radius, radius);
            if (border != null) {
                g2.setColor(border);
                g2.drawRoundRect(0, 0, Math.max(0, getWidth()-2), Math.max(0, getHeight()-3), radius, radius);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    public static class RoundedBorder extends AbstractBorder {
        private final Color color;
        private final int radius;
        private final int thickness;
        public RoundedBorder(Color color, int radius, int thickness) {
            this.color = color; this.radius = radius; this.thickness = thickness;
        }
        @Override public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(thickness));
            int inset = Math.max(1, thickness / 2);
            g2.drawRoundRect(x + inset, y + inset, width - thickness - 1, height - thickness - 1, radius, radius);
            g2.dispose();
        }
        @Override public Insets getBorderInsets(Component c) {
            int i = Math.max(1, thickness);
            return new Insets(i, i, i, i);
        }
        @Override public Insets getBorderInsets(Component c, Insets insets) {
            int i = Math.max(1, thickness);
            insets.set(i, i, i, i);
            return insets;
        }
    }


    private static class ModernTabbedPaneUI extends BasicTabbedPaneUI {
        @Override protected void installDefaults() {
            super.installDefaults();
            tabAreaInsets = new Insets(4,4,6,4);
            selectedTabPadInsets = new Insets(0,0,0,0);
            tabInsets = new Insets(8,14,8,14);
            contentBorderInsets = new Insets(6,0,0,0);
        }
        @Override protected void paintTabBackground(Graphics g,int tabPlacement,int tabIndex,int x,int y,int w,int h,boolean isSelected) {
            Graphics2D g2=(Graphics2D)g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            if(isSelected){g2.setColor(BLUE_SOFT);g2.fillRoundRect(x+2,y+2,w-4,h-4,12,12);}
            g2.dispose();
        }
        @Override protected void paintTabBorder(Graphics g,int tabPlacement,int tabIndex,int x,int y,int w,int h,boolean isSelected) {}
        @Override protected void paintContentBorder(Graphics g,int tabPlacement,int selectedIndex) {}
        @Override protected void paintFocusIndicator(Graphics g,int tabPlacement,Rectangle[] rects,int tabIndex,Rectangle iconRect,Rectangle textRect,boolean isSelected) {}
        @Override protected void paintText(Graphics g,int tabPlacement,Font font,FontMetrics metrics,int tabIndex,String title,Rectangle textRect,boolean isSelected) {
            g.setFont(font);g.setColor(isSelected?BLUE:MUTED);
            int mnem=tabPane.getDisplayedMnemonicIndexAt(tabIndex);
            SwingUtilities2Compat.drawStringUnderlineCharAt(tabPane,g,title,mnem,textRect.x,textRect.y+metrics.getAscent());
        }
    }

    /** Small compatibility helper: avoids dependency on internal sun.swing classes. */
    private static final class SwingUtilities2Compat {
        static void drawStringUnderlineCharAt(JComponent c,Graphics g,String text,int underlinedIndex,int x,int y){
            g.drawString(text,x,y);
            if(underlinedIndex>=0 && underlinedIndex<text.length()){
                FontMetrics fm=g.getFontMetrics();
                int ux=x+fm.stringWidth(text.substring(0,underlinedIndex));
                int uw=fm.charWidth(text.charAt(underlinedIndex));
                g.drawLine(ux,y+1,ux+uw,y+1);
            }
        }
    }
    private static class RoundedButtonUI extends BasicButtonUI {
        private final Color base, hover;
        private final int radius;
        RoundedButtonUI(Color base, Color hover, int radius) {
            this.base = base; this.hover = hover; this.radius = radius;
        }
        @Override public void installUI(JComponent c) {
            super.installUI(c);
            if (c instanceof AbstractButton b) {
                b.setOpaque(false);
                b.setBorderPainted(false);
                b.setContentAreaFilled(false);
                b.setRolloverEnabled(true);
            }
        }
        @Override public void paint(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            ButtonModel m = b.getModel();
            Color fill = m.isPressed() ? hover.darker() : (m.isRollover() ? hover : base);
            if (!b.isEnabled()) fill = new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 105);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, c.getWidth(), c.getHeight(), radius, radius);
            g2.dispose();
            super.paint(g, c);
        }
    }
}
