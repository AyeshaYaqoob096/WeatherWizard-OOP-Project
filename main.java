package weatherwizard;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.basic.BasicTextFieldUI;

public class WeatherWizard extends JFrame {

    // ── Theme gradients [top, bottom] ─────────────────────────────────────────
    // 0=default/home  1=sunny  2=partly cloudy  3=overcast  4=rain  5=snow  6=storm
    static final Color[][] T = {
        { new Color(0x1a1a2e), new Color(0x16213e) },   // 0 home (deep navy)
        { new Color(0xFF8C00), new Color(0xFFD700) },   // 1 sunny
        { new Color(0x2196F3), new Color(0x1565C0) },   // 2 partly cloudy
        { new Color(0x607D8B), new Color(0x37474F) },   // 3 overcast/fog
        { new Color(0x1A237E), new Color(0x0D47A1) },   // 4 rain (deep blue)
        { new Color(0xB0BEC5), new Color(0x78909C) },   // 5 snow (icy gray-blue)
        { new Color(0x212121), new Color(0x1A237E) },   // 6 thunderstorm (near black)
    };

    static final Color W  = Color.WHITE;
    static final Color WD = new Color(255, 255, 255, 170);
    static final Color WF = new Color(255, 255, 255,  90);
    static final Color ER = new Color(255, 80, 80);

    // ── Animation state ───────────────────────────────────────────────────────
    private int   themeIdx  = 0;          // current theme (used for art type)
    private int   spinAngle = 0;
    private boolean loading = false;
    private Color animTop   = T[0][0];
    private Color animBot   = T[0][1];
    private Color tgtTop    = T[0][0];
    private Color tgtBot    = T[0][1];

    // Rain / snow particles
    private final float[] pX  = new float[80];
    private final float[] pY  = new float[80];
    private final float[] pSp = new float[80];   // speed
    private final float[] pA  = new float[80];   // alpha 0-1
    private final Random  rng = new Random(42);

    private Timer mainTimer;  // drives spinner, bg lerp, and particles

    // ── UI refs ───────────────────────────────────────────────────────────────
    private BgPanel    bgPanel;
    private JTextField searchField;
    private JButton    goBtn;
    private JLabel     statusLbl;
    private JPanel     mainContent;
    private JPanel     welcomePanel;

    // Weather labels
    private JLabel iconLbl, tempLbl, condLbl, cityLbl;
    private JLabel humVal, windVal, pressVal, visVal;
    private JPanel hourlyRow, dailyList;
    private JPanel currentSection, metricsSection, hourlySection, dailySection;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(12)).build();

    // ═════════════════════════════════════════════════════════════════════════
    public WeatherWizard() {
        setTitle("WeatherWizard");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(420, 800);
        setMinimumSize(new Dimension(380, 600));
        setLocationRelativeTo(null);

        bgPanel = new BgPanel();
        bgPanel.setLayout(new BorderLayout());
        setContentPane(bgPanel);

        initParticles();

        // One master timer: spinner + bg lerp + particles
        mainTimer = new Timer(30, e -> {
            spinAngle = (spinAngle + 6) % 360;
            float step = 0.05f;
            animTop = lerp(animTop, tgtTop, step);
            animBot = lerp(animBot, tgtBot, step);
            tickParticles();
            bgPanel.repaint();
        });
        mainTimer.start();

        buildLayout();
    }

    // ── Particle init ─────────────────────────────────────────────────────────
    void initParticles() {
        for (int i = 0; i < pX.length; i++) resetParticle(i, true);
    }

    void resetParticle(int i, boolean randomY) {
        pX[i] = rng.nextFloat() * 420;
        pY[i] = randomY ? rng.nextFloat() * 800 : -10;
        pSp[i] = 2f + rng.nextFloat() * 4f;
        pA[i]  = 0.3f + rng.nextFloat() * 0.6f;
    }

    void tickParticles() {
        int theme = themeIdx;
        if (theme != 4 && theme != 5 && theme != 6) return;   // only for rain/snow/storm
        for (int i = 0; i < pX.length; i++) {
            pY[i] += pSp[i];
            if (theme == 5) pX[i] += (rng.nextFloat() - 0.5f) * 1.5f;  // snow drift
            if (pY[i] > 820) resetParticle(i, false);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Background panel — draws gradient + weather art + particles + spinner
    // ══════════════════════════════════════════════════════════════════════════
    class BgPanel extends JPanel {
        BgPanel() { setOpaque(true); }

        @Override protected void paintComponent(Graphics g) {
            int W = getWidth(), H = getHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,    RenderingHints.VALUE_STROKE_PURE);

            // Gradient background
            g2.setPaint(new GradientPaint(0, 0, animTop, 0, H, animBot));
            g2.fillRect(0, 0, W, H);

            // Theme art — clipped to sky strip (top 28%) so nothing overlaps content
            Shape origClip = g2.getClip();
            int skyH = H * 28 / 100;
            g2.clipRect(0, 0, W, skyH);
            switch (themeIdx) {
                case 0 -> drawHome(g2, W, H);
                case 1 -> drawSunny(g2, W, H);
                case 2 -> drawPartlyCloudy(g2, W, H);
                case 3 -> drawOvercast(g2, W, H);
                case 4 -> drawRainy(g2, W, H);
                case 5 -> drawSnowy(g2, W, H);
                case 6 -> drawStormy(g2, W, H);
            }
            g2.setClip(origClip);
            // Particles (rain/snow) go full height intentionally
            if (themeIdx == 4 || themeIdx == 5 || themeIdx == 6) drawParticles(g2);

            // Loading overlay + spinner
            if (loading) {
                g2.setColor(new Color(0, 0, 0, 120));
                g2.fillRect(0, 0, W, H);
                int cx = W / 2, cy = H / 2, r = 32;
                g2.setColor(new Color(255, 255, 255, 35));
                g2.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawOval(cx - r, cy - r, r * 2, r * 2);
                g2.setColor(new Color(255, 255, 255, 230));
                g2.drawArc(cx - r, cy - r, r * 2, r * 2, -spinAngle, 100);
                g2.setFont(new Font("Dialog", Font.BOLD, 14));
                g2.setColor(new Color(255, 255, 255, 200));
                String msg = "Fetching weather...";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(msg, cx - fm.stringWidth(msg) / 2, cy + r + 28);
            }
            g2.dispose();
        }
    }

    // ── Home screen art — stars + moon ────────────────────────────────────────
    void drawHome(Graphics2D g, int W, int H) {
        // Stars
        rng.setSeed(7);
        for (int i = 0; i < 60; i++) {
            float x = rng.nextFloat() * W;
            float y = rng.nextFloat() * H * 0.7f;
            float s = 0.5f + rng.nextFloat() * 2.5f;
            float a = 0.3f + rng.nextFloat() * 0.7f;
            g.setColor(new Color(1f, 1f, 1f, a));
            g.fill(new Ellipse2D.Float(x - s/2, y - s/2, s, s));
        }

        // Moon
        int mx = W / 2 + 40, my = H / 4;
        g.setColor(new Color(255, 245, 200, 220));
        g.fillOval(mx - 55, my - 55, 110, 110);
        // Shadow bite
        g.setColor(T[0][0]);
        g.fillOval(mx - 35, my - 60, 105, 105);

        // Moon craters (subtle)
        g.setColor(new Color(220, 210, 170, 60));
        g.fillOval(mx + 10, my - 10, 18, 18);
        g.fillOval(mx - 20, my + 20, 12, 12);

        // Hint text
        g.setFont(new Font("Dialog", Font.BOLD, 18));
        g.setColor(new Color(255, 255, 255, 180));
        String h1 = "Search any city";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(h1, (W - fm.stringWidth(h1)) / 2, H * 2 / 3);
        g.setFont(new Font("Dialog", Font.PLAIN, 13));
        g.setColor(new Color(255, 255, 255, 110));
        String h2 = "to see live weather";
        g.drawString(h2, (W - g.getFontMetrics().stringWidth(h2)) / 2, H * 2 / 3 + 24);
    }

    // ── Sunny art — big radiant sun ───────────────────────────────────────────
    void drawSunny(Graphics2D g, int W, int H) {
        int cx = W / 2, cy = H / 5;
        // Outer glow
        RadialGradientPaint rp = new RadialGradientPaint(cx, cy, 140,
                new float[]{0f, 0.4f, 1f},
                new Color[]{new Color(255,255,200,120), new Color(255,200,0,40), new Color(255,160,0,0)});
        g.setPaint(rp);
        g.fillOval(cx - 140, cy - 140, 280, 280);

        // Sun rays
        g.setColor(new Color(255, 235, 100, 100));
        g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 12; i++) {
            double ang = Math.toRadians(i * 30);
            int x1 = (int)(cx + 68 * Math.cos(ang)),  y1 = (int)(cy + 68 * Math.sin(ang));
            int x2 = (int)(cx + 105 * Math.cos(ang)), y2 = (int)(cy + 105 * Math.sin(ang));
            g.drawLine(x1, y1, x2, y2);
        }
        // Sun disc
        RadialGradientPaint sp = new RadialGradientPaint(cx - 10, cy - 10, 58,
                new float[]{0f, 1f},
                new Color[]{new Color(255,250,200), new Color(255,180,0)});
        g.setPaint(sp);
        g.fillOval(cx - 58, cy - 58, 116, 116);

        // Horizon shimmer
        g.setPaint(new GradientPaint(0, H - 120, new Color(255,200,50,60), 0, H, new Color(255,140,0,0)));
        g.fillRect(0, H - 120, W, 120);
    }

    // ── Partly cloudy art ─────────────────────────────────────────────────────
    void drawPartlyCloudy(Graphics2D g, int W, int H) {
        // Small sun top-right
        int sx = W * 3 / 4, sy = H / 6;
        g.setColor(new Color(255, 230, 100, 160));
        for (int i = 0; i < 8; i++) {
            double ang = Math.toRadians(i * 45);
            int x1 = (int)(sx + 38*Math.cos(ang)), y1 = (int)(sy + 38*Math.sin(ang));
            int x2 = (int)(sx + 56*Math.cos(ang)), y2 = (int)(sy + 56*Math.sin(ang));
            g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x1, y1, x2, y2);
        }
        g.setColor(new Color(255, 220, 80, 200));
        g.fillOval(sx - 32, sy - 32, 64, 64);

        // Clouds
        drawCloud(g, W / 2 - 30, H / 4, 1.2f, 200);
        drawCloud(g, W / 4,       H / 3, 0.8f, 150);
    }

    // ── Overcast ──────────────────────────────────────────────────────────────
    void drawOvercast(Graphics2D g, int W, int H) {
        drawCloud(g, W / 2 - 20, H / 5,      1.4f, 180);
        drawCloud(g, W / 4 - 20, H / 4 + 30, 1.0f, 140);
        drawCloud(g, W * 3/4,    H / 4,      1.1f, 160);
        drawCloud(g, W / 2 + 10, H / 3 + 20, 0.9f, 120);
    }

    // ── Rainy art (clouds only — particles drawn after clip restore) ──────────
    void drawRainy(Graphics2D g, int W, int H) {
        drawCloud(g, W / 2 - 20, H / 8,  1.3f, 180);
        drawCloud(g, W / 4,      H / 6,  0.9f, 140);
    }

    // ── Snowy art (cloud only) ────────────────────────────────────────────────
    void drawSnowy(Graphics2D g, int W, int H) {
        drawCloud(g, W / 2 - 10, H / 8, 1.2f, 200);
    }

    // ── Stormy art (clouds only) ──────────────────────────────────────────────
    void drawStormy(Graphics2D g, int W, int H) {
        drawCloud(g, W / 2 - 30, H / 9,  1.5f, 160);
        drawCloud(g, W / 3 - 20, H / 6,  1.1f, 130);
    }

    // ── Particles drawn full-height AFTER clip is restored ────────────────────
    void drawParticles(Graphics2D g) {
        switch (themeIdx) {
            case 4 -> {   // rain
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < pX.length; i++) {
                    g.setColor(new Color(160, 200, 255, (int)(pA[i] * 180)));
                    g.drawLine((int)pX[i], (int)pY[i], (int)(pX[i]-3), (int)(pY[i]+12));
                }
            }
            case 5 -> {   // snow
                for (int i = 0; i < pX.length; i++) {
                    float sz = 3 + pSp[i];
                    g.setColor(new Color(255, 255, 255, (int)(pA[i] * 200)));
                    drawSnowflake(g, pX[i], pY[i], sz);
                }
            }
            case 6 -> {   // storm — heavy rain + lightning
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < pX.length; i++) {
                    g.setColor(new Color(130, 160, 220, (int)(pA[i] * 160)));
                    g.drawLine((int)pX[i], (int)pY[i], (int)(pX[i]-5), (int)(pY[i]+16));
                }
                // Lightning bolt below the sky strip (always visible)
                drawLightning(g, bgPanel.getWidth() / 2 + 30, bgPanel.getHeight() / 4);
            }
        }
    }

    void drawSnowflake(Graphics2D g, float x, float y, float r) {
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 6; i++) {
            double ang = Math.toRadians(i * 60);
            g.drawLine((int)x, (int)y,
                    (int)(x + r * Math.cos(ang)), (int)(y + r * Math.sin(ang)));
        }
    }

    void drawLightning(Graphics2D g, int x, int y) {
        int[] px = { x, x-14, x+4, x-12, x+18, x+2, x-8 };
        int[] py = { y, y+28, y+28, y+56, y+56, y+82, y+82 };
        g.setColor(new Color(255, 255, 100, 60));
        g.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawPolyline(px, py, px.length);
        g.setColor(new Color(255, 255, 180, 210));
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawPolyline(px, py, px.length);
    }

    // ── Cloud shape helper — Area union gives clean merged silhouette ─────────
    void drawCloud(Graphics2D g, int cx, int cy, float sc, int alpha) {
        float s = 80 * sc;
        // Five overlapping ellipses — Area.add merges them into one outline
        Area cloud = new Area();
        // Centre-left big bump
        cloud.add(new Area(new Ellipse2D.Float(cx,           cy,           s*0.65f, s*0.65f)));
        // Top-centre (tallest bump)
        cloud.add(new Area(new Ellipse2D.Float(cx + s*0.32f, cy - s*0.22f, s*0.58f, s*0.58f)));
        // Right bump
        cloud.add(new Area(new Ellipse2D.Float(cx + s*0.72f, cy + s*0.04f, s*0.52f, s*0.52f)));
        // Far-left small bump
        cloud.add(new Area(new Ellipse2D.Float(cx - s*0.18f, cy + s*0.12f, s*0.40f, s*0.40f)));
        // Far-right small bump
        cloud.add(new Area(new Ellipse2D.Float(cx + s*1.04f, cy + s*0.15f, s*0.34f, s*0.34f)));
        // Flat base — fills the notches between bottom of circles
        cloud.add(new Area(new Rectangle2D.Float(cx - s*0.18f, cy + s*0.36f, s*1.56f, s*0.30f)));

        g.setColor(new Color(255, 255, 255, alpha));
        g.fill(cloud);

        // Soft inner highlight along top edge
        g.setColor(new Color(255, 255, 255, Math.min(255, alpha + 40)));
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(cloud);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UI Layout
    // ═════════════════════════════════════════════════════════════════════════
    void buildLayout() {
        // ── Top: title + search ──────────────────────────────────────────────
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(new EmptyBorder(24, 20, 8, 20));

        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        titleRow.setOpaque(false);
        titleRow.add(lbl("🌤", 22, Font.PLAIN, W));
        titleRow.add(lbl("WeatherWizard", 22, Font.BOLD,  W));
        top.add(titleRow);
        top.add(Box.createVerticalStrut(16));

        // Pill search bar
        JPanel pill = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(255,255,255,42));
                g2.fillRoundRect(0,0,getWidth(),getHeight(),50,50);
                g2.setColor(new Color(255,255,255,80));
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,50,50);
                g2.dispose();
            }
        };
        pill.setOpaque(false);
        pill.setPreferredSize(new Dimension(360, 48));
        pill.setMaximumSize(new Dimension(9999, 48));

        JLabel ico = lbl(" 🔍", 15, Font.PLAIN, WD);
        searchField = new JTextField("Search city...");
        searchField.setUI(new BasicTextFieldUI());
        searchField.setOpaque(false);
        searchField.setBackground(new Color(0,0,0,0));
        searchField.setBorder(new EmptyBorder(0,6,0,6));
        searchField.setFont(new Font("Dialog", Font.PLAIN, 14));
        searchField.setForeground(WD);
        searchField.setCaretColor(W);
        searchField.addFocusListener(new FocusAdapter() {
            public void focusGained(FocusEvent e) {
                if (searchField.getText().equals("Search city...")) { searchField.setText(""); searchField.setForeground(W); }
            }
            public void focusLost(FocusEvent e) {
                if (searchField.getText().isEmpty()) { searchField.setForeground(WD); searchField.setText("Search city..."); }
            }
        });

        goBtn = new JButton("GO") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isPressed() ? new Color(255,255,255,70)
                          : getModel().isRollover()? new Color(255,255,255,55)
                          : new Color(255,255,255,35));
                g2.fillRoundRect(0,0,getWidth(),getHeight(),48,48);
                g2.setFont(new Font("Dialog",Font.BOLD,13));
                g2.setColor(W);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),(getWidth()-fm.stringWidth(getText()))/2,(getHeight()+fm.getAscent()-fm.getDescent())/2);
                g2.dispose();
            }
        };
        goBtn.setOpaque(false); goBtn.setContentAreaFilled(false);
        goBtn.setBorderPainted(false); goBtn.setFocusPainted(false);
        goBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        goBtn.setPreferredSize(new Dimension(54, 48));

        pill.add(ico, BorderLayout.WEST);
        pill.add(searchField, BorderLayout.CENTER);
        pill.add(goBtn, BorderLayout.EAST);

        JPanel pillRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        pillRow.setOpaque(false); pillRow.add(pill);
        pillRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        top.add(pillRow);

        statusLbl = lbl("", 12, Font.PLAIN, ER);
        statusLbl.setHorizontalAlignment(SwingConstants.CENTER);
        statusLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        top.add(Box.createVerticalStrut(5));
        top.add(statusLbl);

        bgPanel.add(top, BorderLayout.NORTH);

        // ── Scroll content ───────────────────────────────────────────────────
        mainContent = new JPanel();
        mainContent.setOpaque(false);
        mainContent.setLayout(new BoxLayout(mainContent, BoxLayout.Y_AXIS));
        mainContent.setBorder(new EmptyBorder(4, 16, 28, 16));

        buildCurrentSection();
        buildMetricsSection();
        buildHourlySection();
        buildDailySection();

        currentSection.setVisible(false);
        metricsSection.setVisible(false);
        hourlySection.setVisible(false);
        dailySection.setVisible(false);

        JScrollPane scroll = new JScrollPane(mainContent,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(3,0));
        scroll.getVerticalScrollBar().setUnitIncrement(14);

        bgPanel.add(scroll, BorderLayout.CENTER);

        ActionListener go = e -> startSearch();
        goBtn.addActionListener(go); searchField.addActionListener(go);
    }

    // ── Current weather card ──────────────────────────────────────────────────
    void buildCurrentSection() {
        currentSection = glass(22, new EmptyBorder(24, 20, 20, 20));
        currentSection.setLayout(new BoxLayout(currentSection, BoxLayout.Y_AXIS));

        iconLbl = lbl("",   72, Font.PLAIN, W); iconLbl.setAlignmentX(CENTER_ALIGNMENT);
        tempLbl = lbl("",   62, Font.BOLD,  W); tempLbl.setAlignmentX(CENTER_ALIGNMENT);
        condLbl = lbl("",   14, Font.PLAIN, WD); condLbl.setAlignmentX(CENTER_ALIGNMENT);
        cityLbl = lbl("",   17, Font.BOLD,  W); cityLbl.setAlignmentX(CENTER_ALIGNMENT);

        currentSection.add(iconLbl);
        currentSection.add(Box.createVerticalStrut(6));
        currentSection.add(tempLbl);
        currentSection.add(Box.createVerticalStrut(3));
        currentSection.add(condLbl);
        currentSection.add(Box.createVerticalStrut(10));
        currentSection.add(hline());
        currentSection.add(Box.createVerticalStrut(10));
        currentSection.add(cityLbl);

        mainContent.add(currentSection);
        mainContent.add(Box.createVerticalStrut(10));
    }

    // ── Metrics card ─────────────────────────────────────────────────────────
    void buildMetricsSection() {
        metricsSection = glass(18, new EmptyBorder(14, 20, 14, 20));
        metricsSection.setLayout(new BoxLayout(metricsSection, BoxLayout.Y_AXIS));

        humVal   = lbl("", 14, Font.BOLD, W);
        windVal  = lbl("", 14, Font.BOLD, W);
        pressVal = lbl("", 14, Font.BOLD, W);
        visVal   = lbl("", 14, Font.BOLD, W);

        metricsSection.add(mRow("💧  Humidity",   humVal));
        metricsSection.add(mDiv());
        metricsSection.add(mRow("🌬   Wind Speed", windVal));
        metricsSection.add(mDiv());
        metricsSection.add(mRow("📊  Pressure",   pressVal));
        metricsSection.add(mDiv());
        metricsSection.add(mRow("👁   Visibility", visVal));

        mainContent.add(metricsSection);
        mainContent.add(Box.createVerticalStrut(10));
    }

    JPanel mRow(String label, JLabel val) {
        JPanel r = new JPanel(new BorderLayout()); r.setOpaque(false);
        r.setBorder(new EmptyBorder(8,0,8,0));
        r.add(lbl(label, 13, Font.PLAIN, WD), BorderLayout.WEST);
        r.add(val, BorderLayout.EAST);
        return r;
    }
    JComponent mDiv() {
        JPanel d = new JPanel(); d.setOpaque(false);
        d.setMaximumSize(new Dimension(9999,1)); d.setPreferredSize(new Dimension(1,1));
        d.setBorder(BorderFactory.createMatteBorder(1,0,0,0,new Color(255,255,255,28)));
        return d;
    }

    // ── Hourly card ───────────────────────────────────────────────────────────
    void buildHourlySection() {
        hourlySection = glass(18, new EmptyBorder(14,14,14,14));
        hourlySection.setLayout(new BoxLayout(hourlySection, BoxLayout.Y_AXIS));

        JLabel hdr = lbl("⏱  Hourly Forecast", 13, Font.BOLD, WD);
        hdr.setAlignmentX(LEFT_ALIGNMENT);
        hourlySection.add(hdr);
        hourlySection.add(Box.createVerticalStrut(10));

        hourlyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        hourlyRow.setOpaque(false);
        hourlyRow.setAlignmentX(LEFT_ALIGNMENT);

        JScrollPane hs = new JScrollPane(hourlyRow,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        hs.setOpaque(false); hs.getViewport().setOpaque(false); hs.setBorder(null);
        hs.setMaximumSize(new Dimension(9999, 100));
        hs.setAlignmentX(LEFT_ALIGNMENT);
        hs.getHorizontalScrollBar().setPreferredSize(new Dimension(0,3));
        hourlySection.add(hs);

        mainContent.add(hourlySection);
        mainContent.add(Box.createVerticalStrut(10));
    }

    JPanel hCard(String time, String ico, double temp) {
        JPanel c = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(255,255,255,28));
                g2.fillRoundRect(0,0,getWidth(),getHeight(),14,14);
                g2.setColor(new Color(255,255,255,50));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,14,14);
                g2.dispose();
            }
        };
        c.setOpaque(false);
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        c.setBorder(new EmptyBorder(10,10,10,10));
        c.setPreferredSize(new Dimension(64, 88));
        JLabel tl = lbl(time, 10, Font.BOLD, WD); tl.setAlignmentX(CENTER_ALIGNMENT);
        JLabel il = lbl(ico,  22, Font.PLAIN, W);  il.setAlignmentX(CENTER_ALIGNMENT);
        JLabel vl = lbl(String.format("%.0f°", temp), 13, Font.BOLD, W); vl.setAlignmentX(CENTER_ALIGNMENT);
        c.add(tl); c.add(Box.createVerticalStrut(4));
        c.add(il); c.add(Box.createVerticalStrut(3));
        c.add(vl);
        return c;
    }

    // ── Daily card ────────────────────────────────────────────────────────────
    void buildDailySection() {
        dailySection = glass(18, new EmptyBorder(14,14,6,14));
        dailySection.setLayout(new BoxLayout(dailySection, BoxLayout.Y_AXIS));

        JLabel hdr = lbl("📅  7-Day Forecast", 13, Font.BOLD, WD);
        hdr.setAlignmentX(LEFT_ALIGNMENT);
        dailySection.add(hdr);
        dailySection.add(Box.createVerticalStrut(6));

        dailyList = new JPanel();
        dailyList.setOpaque(false);
        dailyList.setLayout(new BoxLayout(dailyList, BoxLayout.Y_AXIS));
        dailySection.add(dailyList);

        mainContent.add(dailySection);
    }

    JPanel dRow(String day, String ico, double hi, double lo) {
        JPanel r = new JPanel(new BorderLayout()); r.setOpaque(false);
        r.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0,0,1,0,new Color(255,255,255,18)),
                new EmptyBorder(10,4,10,4)));
        JLabel dl = lbl(day, 13, Font.BOLD, W);
        JLabel il = lbl(ico, 16, Font.PLAIN, W); il.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel tp = new JPanel(new FlowLayout(FlowLayout.RIGHT,4,0)); tp.setOpaque(false);
        tp.add(lbl(String.format("%.0f°", hi), 13, Font.BOLD, W));
        tp.add(lbl(String.format("/ %.0f°", lo), 13, Font.PLAIN, WD));
        r.add(dl, BorderLayout.WEST); r.add(il, BorderLayout.CENTER); r.add(tp, BorderLayout.EAST);
        return r;
    }

    // ── Search trigger ────────────────────────────────────────────────────────
    void startSearch() {
        String city = searchField.getText().trim();
        if (city.isEmpty() || city.equals("Search city...")) return;
        loading = true; statusLbl.setText(""); goBtn.setEnabled(false);
        currentSection.setVisible(false); metricsSection.setVisible(false);
        hourlySection.setVisible(false);  dailySection.setVisible(false);
        CompletableFuture.supplyAsync(() -> fetchAll(city))
                .thenAcceptAsync(this::applyData, SwingUtilities::invokeLater);
    }

    // ── HTTP fetch ────────────────────────────────────────────────────────────
    WeatherData fetchAll(String city) {
        try {
            String enc = URLEncoder.encode(city, StandardCharsets.UTF_8);
            String geo = get("https://geocoding-api.open-meteo.com/v1/search?name=" + enc + "&count=1&language=en&format=json");
            if (!geo.contains("\"results\"")) return WeatherData.err("City not found.");

            String res = geo.substring(geo.indexOf("\"results\""));
            double lat = pd(res,"latitude"), lon = pd(res,"longitude");
            String name = ps(res,"name"), country = ps(res,"country");

            String wx = get("https://api.open-meteo.com/v1/forecast"
                    + "?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,"
                    + "weather_code,wind_speed_10m,surface_pressure,visibility"
                    + "&hourly=temperature_2m,weather_code"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                    + "&temperature_unit=celsius&wind_speed_unit=kmh"
                    + "&forecast_days=7&timezone=auto");

            int ci = wx.lastIndexOf("\"current\""); if (ci < 0) return WeatherData.err("API error.");
            String cur = wx.substring(ci);
            double temp = pd(cur,"temperature_2m"), feels = pd(cur,"apparent_temperature");
            int hum=(int)pd(cur,"relative_humidity_2m"); double wind=pd(cur,"wind_speed_10m");
            int code=(int)pd(cur,"weather_code"); double pres=pd(cur,"surface_pressure"), vis=pd(cur,"visibility");
            String curTime=ps(cur,"time");

            int hStart=wx.indexOf("\"hourly\""), hEnd=wx.indexOf("\"daily\"");
            String hourly=wx.substring(hStart,hEnd);
            String[] hTimes=psa(hourly,"time"); double[] hTemps=pda(hourly,"temperature_2m"); double[] hCodes=pda(hourly,"weather_code");
            int hIdx=0; String pref=curTime.length()>=13?curTime.substring(0,13):curTime;
            for(int i=0;i<hTimes.length;i++){if(hTimes[i].startsWith(pref)){hIdx=i;break;}}
            int hLen=Math.min(8,hTimes.length-hIdx);
            String[] h8t=new String[hLen]; double[] h8v=new double[hLen]; int[] h8c=new int[hLen];
            for(int i=0;i<hLen;i++){h8t[i]=hTimes[hIdx+i];h8v[i]=hTemps[hIdx+i];h8c[i]=(int)hCodes[hIdx+i];}

            int dStart=wx.lastIndexOf("\"daily\""); String daily=wx.substring(dStart);
            String[] dTimes=psa(daily,"time"); double[] dMax=pda(daily,"temperature_2m_max"); double[] dMin=pda(daily,"temperature_2m_min"); double[] dC=pda(daily,"weather_code");
            int dLen=Math.min(7,dTimes.length); int[] dCodes=new int[dLen];
            for(int i=0;i<dLen;i++) dCodes[i]=(int)dC[i];

            return new WeatherData(name,country,temp,feels,hum,wind,code,pres,vis,h8t,h8v,h8c,dTimes,dMax,dMin,dCodes,dLen,null);
        } catch(Exception ex){ return WeatherData.err("Error: "+ex.getMessage()); }
    }

    // ── Apply data to UI ──────────────────────────────────────────────────────
    void applyData(WeatherData d) {
        loading = false; goBtn.setEnabled(true);
        if (d.error != null) { statusLbl.setText(d.error); bgPanel.repaint(); return; }
        statusLbl.setText("");

        themeIdx = themeFor(d.code);
        tgtTop = T[themeIdx][0]; tgtBot = T[themeIdx][1];
        initParticles();   // reset particles for new theme

        iconLbl.setText(icon(d.code));
        tempLbl.setText(String.format("%.0f°C", d.temp));
        condLbl.setText(desc(d.code) + "  ·  Feels " + String.format("%.0f°C", d.feels));
        cityLbl.setText("📍 " + d.city + ", " + d.country);

        humVal.setText(d.humidity + "%");
        windVal.setText(String.format("%.0f km/h", d.wind));
        pressVal.setText(String.format("%.0f hPa", d.pressure));
        visVal.setText(String.format("%.0f km", d.visibility / 1000.0));

        hourlyRow.removeAll();
        for (int i = 0; i < d.hourlyTimes.length; i++)
            hourlyRow.add(hCard(fmtH(d.hourlyTimes[i]), icon(d.hourlyCodes[i]), d.hourlyTemps[i]));

        dailyList.removeAll();
        for (int i = 0; i < d.dailyLen; i++)
            dailyList.add(dRow(i==0?"Today":dayN(d.dailyTimes[i]), icon(d.dailyCodes[i]), d.dailyMax[i], d.dailyMin[i]));

        currentSection.setVisible(true); metricsSection.setVisible(true);
        hourlySection.setVisible(true);  dailySection.setVisible(true);
        mainContent.revalidate(); bgPanel.repaint(); bgPanel.revalidate();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    JPanel glass(int arc, EmptyBorder pad) {
        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(255,255,255,38));
                g2.fillRoundRect(0,0,getWidth(),getHeight(),arc,arc);
                g2.setColor(new Color(255,255,255,65));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,arc,arc);
                g2.dispose();
            }
        };
        p.setOpaque(false); p.setBorder(pad); return p;
    }

    JComponent hline() {
        JPanel l = new JPanel(); l.setOpaque(false);
        l.setMaximumSize(new Dimension(9999,1));
        l.setBorder(BorderFactory.createMatteBorder(1,0,0,0,new Color(255,255,255,40)));
        return l;
    }

    JLabel lbl(String t, int sz, int st, Color c) {
        JLabel l = new JLabel(t); l.setFont(new Font("Dialog",st,sz)); l.setForeground(c); return l;
    }

    Color lerp(Color a, Color b, float t) {
        return new Color(
            Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * t),
            Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
    }

    // ── JSON ──────────────────────────────────────────────────────────────────
    double pd(String j, String k) {
        int i=j.indexOf("\""+k+"\""); if(i<0)throw new RuntimeException("Missing: "+k);
        int s=j.indexOf(':',i)+1; while(s<j.length()&&" \n\r\t".indexOf(j.charAt(s))>=0)s++;
        if(j.charAt(s)=='"')throw new RuntimeException("Not numeric: "+k);
        int e=s; while(e<j.length()&&(Character.isDigit(j.charAt(e))||j.charAt(e)=='.'||j.charAt(e)=='-'))e++;
        return Double.parseDouble(j.substring(s,e));
    }
    String ps(String j, String k) {
        int i=j.indexOf("\""+k+"\""); if(i<0)return "";
        int q1=j.indexOf('"',j.indexOf(':',i)+1); return j.substring(q1+1,j.indexOf('"',q1+1));
    }
    double[] pda(String j, String k) {
        int i=j.indexOf("\""+k+"\""); if(i<0)return new double[0];
        int s=j.indexOf('[',i)+1,e=j.indexOf(']',s);
        String[] p=j.substring(s,e).split(","); double[] r=new double[p.length];
        for(int x=0;x<p.length;x++)r[x]=Double.parseDouble(p[x].trim()); return r;
    }
    String[] psa(String j, String k) {
        int i=j.indexOf("\""+k+"\""); if(i<0)return new String[0];
        int s=j.indexOf('[',i)+1,e=j.indexOf(']',s);
        String[] p=j.substring(s,e).split(","); String[] r=new String[p.length];
        for(int x=0;x<p.length;x++)r[x]=p[x].trim().replace("\"",""); return r;
    }
    String get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    // ── Time / date ───────────────────────────────────────────────────────────
    String fmtH(String iso) {
        try { int h=LocalDateTime.parse(iso,DateTimeFormatter.ISO_LOCAL_DATE_TIME).getHour();
            return h==0?"12AM":h<12?h+"AM":h==12?"12PM":(h-12)+"PM"; }
        catch(Exception e){return iso.substring(11,16);}
    }
    String dayN(String iso) {
        try { return LocalDate.parse(iso,DateTimeFormatter.ISO_LOCAL_DATE).getDayOfWeek().getDisplayName(TextStyle.SHORT,Locale.ENGLISH); }
        catch(Exception e){return iso;}
    }

    // ── Theme index ───────────────────────────────────────────────────────────
    int themeFor(int c) {
        if(c==0)  return 1;  // sunny
        if(c<=2)  return 2;  // partly cloudy
        if(c<=48) return 3;  // overcast / fog
        if(c<=67) return 4;  // rain
        if(c<=86) return 5;  // snow
        return 6;            // thunderstorm
    }

    String icon(int c) {
        if(c==0)  return "☀️"; if(c<=2) return "🌤️"; if(c==3) return "☁️";
        if(c<=48) return "🌫️"; if(c<=57) return "🌦️"; if(c<=67) return "🌧️";
        if(c<=77) return "❄️"; if(c<=82) return "🌦️"; if(c<=86) return "🌨️";
        if(c==95) return "⛈️"; return "⛈️";
    }
    String desc(int c) {
        if(c==0)  return "Clear Sky";   if(c==1) return "Mainly Clear";
        if(c==2)  return "Partly Cloudy"; if(c==3) return "Overcast";
        if(c<=48) return "Foggy";       if(c<=55) return "Drizzle";
        if(c<=67) return "Rainy";       if(c<=77) return "Snowfall";
        if(c<=82) return "Rain Showers"; if(c<=86) return "Snow Showers";
        if(c==95) return "Thunderstorm"; return "Thunderstorm + Hail";
    }

    // ── Data record ───────────────────────────────────────────────────────────
    record WeatherData(
        String city, String country,
        double temp, double feels, int humidity, double wind, int code,
        double pressure, double visibility,
        String[] hourlyTimes, double[] hourlyTemps, int[] hourlyCodes,
        String[] dailyTimes, double[] dailyMax, double[] dailyMin,
        int[] dailyCodes, int dailyLen, String error
    ) {
        static WeatherData err(String msg) {
            return new WeatherData(null,null,0,0,0,0,0,0,0,
                new String[0],new double[0],new int[0],
                new String[0],new double[0],new double[0],new int[0],0,msg);
        }
    }

    // ── Main ──────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale","1.0");
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
        catch(Exception ignored){}
        SwingUtilities.invokeLater(() -> new WeatherWizard().setVisible(true));
    }
}
