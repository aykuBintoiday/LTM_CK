package client;

import common.ModernUI;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** Modern rounded video surface used by both grid mode and screen-share thumbnails. */
public class VideoTile extends JPanel {
    private volatile BufferedImage image;
    private final JLabel nameLabel = new JLabel("", SwingConstants.LEFT);
    private final JLabel stateLabel = new JLabel("Đang chờ video...", SwingConstants.CENTER);

    public VideoTile(String name) {
        setLayout(new BorderLayout());
        setOpaque(false);
        setBorder(new EmptyBorder(5,5,5,5));

        nameLabel.setText(name);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        nameLabel.setBorder(new EmptyBorder(8,10,8,10));
        nameLabel.setOpaque(true);
        nameLabel.setBackground(new Color(10,15,25,210));

        stateLabel.setForeground(new Color(203,213,225));
        stateLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        add(nameLabel, BorderLayout.SOUTH);
        add(stateLabel, BorderLayout.CENTER);
        setPreferredSize(new Dimension(320, 220));
        setMinimumSize(new Dimension(220, 150));
    }

    public void setDisplayName(String name) {
        SwingUtilities.invokeLater(() -> nameLabel.setText(name));
    }

    public void setState(String state) {
        SwingUtilities.invokeLater(() -> stateLabel.setText(state));
    }

    public void setImage(BufferedImage image) {
        this.image = image;
        SwingUtilities.invokeLater(() -> {
            stateLabel.setVisible(image == null);
            repaint();
        });
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = Math.max(1, getWidth()-2);
        int h = Math.max(1, getHeight()-2);

        // Soft tile shadow + premium dark surface.
        g2.setColor(new Color(0,0,0,55));
        g2.fillRoundRect(4,6,Math.max(1,w-6),Math.max(1,h-7),18,18);
        g2.setColor(new Color(24,31,43));
        g2.fillRoundRect(1,1,Math.max(1,w-4),Math.max(1,h-5),18,18);
        g2.setColor(new Color(55,65,81));
        g2.drawRoundRect(1,1,Math.max(1,w-4),Math.max(1,h-5),18,18);

        BufferedImage img = image;
        if (img != null) {
            Shape oldClip = g2.getClip();
            Shape clip = new RoundRectangle2D.Double(5,5,Math.max(1,getWidth()-10),Math.max(1,getHeight()-10),14,14);
            g2.clip(clip);
            int bottom = Math.max(34, nameLabel.getHeight()+6);
            int availW = Math.max(1, getWidth()-10);
            int availH = Math.max(1, getHeight()-10-bottom);
            double scale = Math.min((double)availW/img.getWidth(), (double)availH/img.getHeight());
            int dw = Math.max(1,(int)(img.getWidth()*scale));
            int dh = Math.max(1,(int)(img.getHeight()*scale));
            int x = 5 + (availW-dw)/2;
            int y = 5 + (availH-dh)/2;
            g2.setColor(Color.BLACK);
            g2.fillRect(5,5,availW,availH);
            g2.drawImage(img,x,y,dw,dh,null);
            g2.setClip(oldClip);
        }
        g2.dispose();
        super.paintComponent(g);
    }
}
