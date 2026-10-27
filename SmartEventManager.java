import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SMART EVENT MANAGEMENT SYSTEM  (single file, Java 17+, Swing)
 * Modules: Dashboard, Events (date/time/venue + clash detection), Participants,
 *          Passes, Certificates, Analysis (charts), AI Assistant.
 * Data auto-saves to  ~/smart_event_data.ser
 */
public class SmartEventManager extends JFrame {

    // ======================= MODELS =======================
    static class EventItem implements Serializable {
        private static final long serialVersionUID = 1L;
        String id, name, category, venue, description = "";
        LocalDate date; LocalTime time;
        int durationMin = 120, capacity = 100; double fee;
        @Override public String toString() { return id + " - " + name; }
    }
    static class Person implements Serializable {
        private static final long serialVersionUID = 1L;
        String id, name, email, phone, dept, eventId, role = "Attendee", feedback = "";
        boolean checkedIn; int rating;
        LocalDateTime registeredAt = LocalDateTime.now();
        @Override public String toString() { return id + " - " + name + " (" + eventId + ")"; }
    }
    static class Store implements Serializable {
        private static final long serialVersionUID = 1L;
        List<EventItem> events = new ArrayList<>();
        List<Person> participants = new ArrayList<>();
        int evCounter, pCounter;
    }

    // ======================= GLOBALS / THEME =======================
    static Store store = new Store();
    static final File DATA = new File(System.getProperty("user.home"), "smart_event_data.ser");
    static final Map<String, Integer> VENUES = new LinkedHashMap<>();
    static final String[] CATS = {"Technical", "Cultural", "Sports", "Workshop", "Seminar", "Business", "Social"};
    static final String[] ROLES = {"Attendee", "VIP", "Speaker", "Volunteer", "Organizer"};
    static final String[] MODELS = {"claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5"};
    static final Color PRIMARY = new Color(79, 70, 229), DARK = new Color(30, 27, 75), BG = new Color(241, 245, 249),
            ACCENT = new Color(16, 185, 129), DANGER = new Color(239, 68, 68), WARN = new Color(245, 158, 11),
            VIOLET = new Color(139, 92, 246), BORDER = new Color(226, 232, 240);
    static final Font F = new Font("SansSerif", Font.PLAIN, 14), FB = new Font("SansSerif", Font.BOLD, 14);
    static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd"), TF = DateTimeFormatter.ofPattern("HH:mm");
    static {
        VENUES.put("Main Auditorium", 500); VENUES.put("Open Air Theatre", 800); VENUES.put("Banquet Hall", 300);
        VENUES.put("Seminar Hall A", 150); VENUES.put("Seminar Hall B", 100); VENUES.put("Computer Lab 1", 60);
        VENUES.put("Conference Room", 40);
    }

    interface Refreshable { void refresh(); }
    final List<Refreshable> panels = new ArrayList<>();
    void refreshAll() { save(); for (Refreshable r : panels) r.refresh(); }

    // ======================= VALIDATION =======================
    static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9](?:[A-Za-z0-9._%+-]{0,62}[A-Za-z0-9])?@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,24}$");
    static final Pattern PHONE = Pattern.compile("^\\+?[0-9][0-9 \\-]{6,14}$");
    static final Pattern NAME = Pattern.compile("^\\p{L}[\\p{L} .'-]{1,59}$");
    static boolean validEmail(String e) { return e != null && e.length() <= 254 && !e.contains("..") && EMAIL.matcher(e).matches(); }
    static boolean validPhone(String p) { return PHONE.matcher(p).matches(); }
    static boolean validName(String n) { return NAME.matcher(n).matches(); }

    static class DL implements javax.swing.event.DocumentListener {
        final Runnable r; DL(Runnable r) { this.r = r; }
        public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
    }
    /** Live green/red border while typing. Empty field = neutral. */
    static void live(JTextField f, java.util.function.Predicate<String> ok) {
        javax.swing.border.Border def = f.getBorder();
        f.getDocument().addDocumentListener(new DL(() -> {
            String s = f.getText().trim();
            if (s.isEmpty()) { f.setBorder(def); return; }
            Color c = ok.test(s) ? ACCENT : DANGER;
            f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(c, 2), BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        }));
    }
    static boolean commit(JSpinner s) {
        try { s.commitEdit(); return true; } catch (java.text.ParseException ex) { return false; }
    }

    // ======================= PERSISTENCE =======================
    static void save() {
        try (ObjectOutputStream o = new ObjectOutputStream(new FileOutputStream(DATA))) { o.writeObject(store); }
        catch (Exception ex) { ex.printStackTrace(); }
    }
    static void load() {
        if (!DATA.exists()) return;
        try (ObjectInputStream i = new ObjectInputStream(new FileInputStream(DATA))) { store = (Store) i.readObject(); }
        catch (Exception ex) { store = new Store(); }
    }

    // ======================= DATA HELPERS =======================
    static EventItem ev(String id) { for (EventItem e : store.events) if (e.id.equals(id)) return e; return null; }
    static Person person(String id) { for (Person p : store.participants) if (p.id.equals(id)) return p; return null; }
    static List<Person> partsOf(String eid) {
        return store.participants.stream().filter(p -> p.eventId.equals(eid)).collect(Collectors.toList());
    }
    static int regCount(String eid) { return partsOf(eid).size(); }
    static int inCount(String eid) { return (int) partsOf(eid).stream().filter(p -> p.checkedIn).count(); }
    static int mins(LocalTime t) { return t.getHour() * 60 + t.getMinute(); }
    static EventItem conflict(String venue, LocalDate d, LocalTime t, int dur, String ignoreId) {
        int a = mins(t), b = a + dur;
        for (EventItem o : store.events) {
            if (o.id != null && o.id.equals(ignoreId)) continue;
            if (!o.venue.equals(venue) || !o.date.equals(d)) continue;
            int c = mins(o.time), dd = c + o.durationMin;
            if (a < dd && c < b) return o;
        }
        return null;
    }

    // ======================= STYLED COMPONENTS =======================
    static class RButton extends JButton {
        final Color base; boolean hover;
        RButton(String t, Color c) {
            super(t); base = c; setFont(FB); setForeground(Color.WHITE);
            setContentAreaFilled(false); setFocusPainted(false); setBorderPainted(false); setOpaque(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR)); setBorder(BorderFactory.createEmptyBorder(9, 18, 9, 18));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = !isEnabled() ? new Color(156, 163, 175) : getModel().isPressed() ? base.darker().darker() : hover ? base.darker() : base;
            g.setColor(c); g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12); g.dispose();
            super.paintComponent(g0);
        }
    }
    static class NavButton extends JButton {
        boolean sel, hover;
        NavButton(String t) {
            super(t); setFont(FB); setForeground(new Color(199, 210, 254));
            setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); setOpaque(false);
            setHorizontalAlignment(SwingConstants.LEFT); setBorder(BorderFactory.createEmptyBorder(0, 28, 0, 0));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 48)); setPreferredSize(new Dimension(230, 48));
            setCursor(new Cursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }
        void setSel(boolean s) { sel = s; setForeground(s ? Color.WHITE : new Color(199, 210, 254)); repaint(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (sel) { g.setColor(PRIMARY); g.fillRoundRect(10, 4, getWidth() - 20, getHeight() - 8, 14, 14); g.setColor(ACCENT); g.fillRoundRect(10, 12, 4, getHeight() - 24, 4, 4); }
            else if (hover) { g.setColor(new Color(255, 255, 255, 28)); g.fillRoundRect(10, 4, getWidth() - 20, getHeight() - 8, 14, 14); }
            g.dispose(); super.paintComponent(g0);
        }
    }
    static class Card extends JPanel {
        Color accent;
        Card() { super(new BorderLayout()); setOpaque(false); setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16)); }
        Card accent(Color c) { accent = c; return this; }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(new Color(15, 23, 42, 14)); g.fillRoundRect(2, 3, w - 4, h - 4, 18, 18);
            g.setColor(Color.WHITE); g.fillRoundRect(0, 0, w - 3, h - 4, 18, 18);
            if (accent != null) {
                Graphics2D g2 = (Graphics2D) g.create(); g2.clip(new RoundRectangle2D.Float(0, 0, w - 3, h - 4, 18, 18));
                g2.setColor(accent); g2.fillRect(0, 0, 6, h); g2.dispose();
            }
            g.setColor(BORDER); g.drawRoundRect(0, 0, w - 4, h - 5, 18, 18);
            g.dispose();
        }
    }
    static class Banner extends JPanel {
        final String t, s;
        Banner(String t, String s) { this.t = t; this.s = s; setOpaque(false); setPreferredSize(new Dimension(0, 80)); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setPaint(new GradientPaint(0, 0, PRIMARY, w, h, VIOLET)); g.fillRoundRect(0, 0, w, h, 20, 20);
            g.setColor(new Color(255, 255, 255, 30)); g.fillOval(w - 120, -40, 160, 160); g.fillOval(w - 220, 30, 90, 90);
            g.setColor(Color.WHITE); g.setFont(new Font("SansSerif", Font.BOLD, 26)); g.drawString(t, 26, 38);
            g.setColor(new Color(224, 231, 255)); g.setFont(new Font("SansSerif", Font.PLAIN, 13)); g.drawString(s, 27, 62);
            g.dispose();
        }
    }

    // ======================= UI HELPERS =======================
    static JButton btn(String t, Color c) { return new RButton(t, c); }
    static JTextField tf(int c) {
        JTextField t = new JTextField(c); t.setFont(F);
        t.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(203, 213, 225)), BorderFactory.createEmptyBorder(3, 5, 3, 5)));
        return t;
    }
    static JLabel lbl(String s) { JLabel l = new JLabel(s); l.setFont(FB); l.setForeground(DARK); return l; }
    static JComponent header(String s, String sub) { return new Banner(s, sub); }
    static void addRow(JPanel p, int r, String label, JComponent c) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = r; g.insets = new Insets(5, 6, 5, 6); g.anchor = GridBagConstraints.WEST;
        p.add(lbl(label), g);
        g = new GridBagConstraints();
        g.gridx = 1; g.gridy = r; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.insets = new Insets(5, 6, 5, 6);
        p.add(c, g);
    }
    static DefaultTableModel model(String... cols) {
        return new DefaultTableModel(cols, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
    }
    static JTable table(DefaultTableModel m) {
        JTable t = new JTable(m); t.setFont(F); t.setRowHeight(30); t.setShowGrid(false); t.setIntercellSpacing(new Dimension(0, 0));
        t.setSelectionBackground(new Color(199, 210, 254)); t.setSelectionForeground(Color.BLACK);
        t.setAutoCreateRowSorter(true); t.setFillsViewportHeight(true);
        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean foc, int r, int c) {
                super.getTableCellRendererComponent(tb, v, sel, false, r, c);
                setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
                if (!sel) { setBackground(r % 2 == 0 ? Color.WHITE : new Color(248, 250, 252)); setForeground(new Color(30, 41, 59)); }
                return this;
            }
        });
        t.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean foc, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(tb, v, false, false, r, c);
                l.setOpaque(true); l.setBackground(DARK); l.setForeground(Color.WHITE); l.setFont(FB);
                l.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10)); return l;
            }
        });
        t.getTableHeader().setPreferredSize(new Dimension(0, 34)); t.getTableHeader().setReorderingAllowed(false);
        return t;
    }
    static JScrollPane sp(Component c) {
        JScrollPane s = new JScrollPane(c); s.setBorder(BorderFactory.createLineBorder(BORDER)); s.getViewport().setBackground(Color.WHITE); return s;
    }
    static String selId(JTable t) { int r = t.getSelectedRow(); return r < 0 ? null : t.getValueAt(r, 0).toString(); }
    static Card card() { return new Card(); }
    static void info(Component c, String m) { JOptionPane.showMessageDialog(c, m, "Info", JOptionPane.INFORMATION_MESSAGE); }
    static void err(Component c, String m) { JOptionPane.showMessageDialog(c, m, "Please check", JOptionPane.ERROR_MESSAGE); }
    static JSpinner dateSpinner() {
        Calendar lo = Calendar.getInstance(), hi = Calendar.getInstance(); lo.set(2000, Calendar.JANUARY, 1); hi.set(2100, Calendar.DECEMBER, 31);
        JSpinner s = new JSpinner(new SpinnerDateModel(new Date(), lo.getTime(), hi.getTime(), Calendar.DAY_OF_MONTH));
        JSpinner.DateEditor ed = new JSpinner.DateEditor(s, "yyyy-MM-dd"); ed.getFormat().setLenient(false);
        s.setEditor(ed); s.setFont(F); s.setToolTipText("Format: yyyy-MM-dd"); return s;
    }
    static JSpinner timeSpinner() {
        JSpinner s = new JSpinner(new SpinnerDateModel(new Date(), null, null, Calendar.MINUTE));
        JSpinner.DateEditor ed = new JSpinner.DateEditor(s, "HH:mm"); ed.getFormat().setLenient(false);
        s.setEditor(ed); s.setFont(F); s.setToolTipText("24-hour format: HH:mm"); return s;
    }
    static LocalDate toDate(JSpinner s) { return ((Date) s.getValue()).toInstant().atZone(ZoneId.systemDefault()).toLocalDate(); }
    static LocalTime toTime(JSpinner s) { return ((Date) s.getValue()).toInstant().atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0); }
    static void setDate(JSpinner s, LocalDate d) { s.setValue(Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant())); }
    static void setTime(JSpinner s, LocalTime t) { s.setValue(Date.from(LocalDate.now().atTime(t).atZone(ZoneId.systemDefault()).toInstant())); }
    static String trunc(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "..."; }

    static void drawCenter(Graphics2D g, String s, int cx, int y, int maxW, Font f) {
        Font cur = f;
        while (g.getFontMetrics(cur).stringWidth(s) > maxW && cur.getSize() > 9) cur = cur.deriveFont((float) cur.getSize() - 1);
        g.setFont(cur); g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2, y);
    }
    static void drawFit(Graphics2D g, String s, int x, int y, int maxW, Font f) {
        Font cur = f;
        while (g.getFontMetrics(cur).stringWidth(s) > maxW && cur.getSize() > 9) cur = cur.deriveFont((float) cur.getSize() - 1);
        g.setFont(cur); g.drawString(s, x, y);
    }

    // ======================= IMAGE RENDERERS (PASS + CERTIFICATE) =======================
    static Color roleColor(String r) {
        switch (r) {
            case "VIP": return new Color(180, 120, 0);
            case "Speaker": return new Color(190, 24, 93);
            case "Volunteer": return new Color(5, 150, 105);
            case "Organizer": return new Color(30, 41, 59);
            default: return PRIMARY;
        }
    }
    static String passCode(EventItem e, Person p) {
        return e.id + "-" + p.id + "-" + String.format("%04X", Math.abs((p.email + e.name).hashCode()) % 65536);
    }
    static BufferedImage renderPass(EventItem e, Person p) {
        int W = 820, H = 320;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
        Color rc = roleColor(p.role);
        g.setPaint(new GradientPaint(0, 0, rc, 0, H, rc.darker().darker())); g.fillRect(0, 0, 230, H);
        g.setColor(Color.WHITE);
        drawCenter(g, "EVENT PASS", 115, 70, 200, new Font("SansSerif", Font.BOLD, 26));
        drawCenter(g, p.role.toUpperCase(), 115, 160, 200, new Font("SansSerif", Font.BOLD, 34));
        drawCenter(g, "ADMIT ONE", 115, 205, 200, new Font("SansSerif", Font.PLAIN, 18));
        drawCenter(g, passCode(e, p), 115, 280, 210, new Font("Monospaced", Font.PLAIN, 14));
        g.setColor(DARK);
        drawFit(g, e.name, 255, 65, 350, new Font("SansSerif", Font.BOLD, 30));
        g.setColor(Color.GRAY); g.setFont(new Font("SansSerif", Font.PLAIN, 13)); g.drawString("PARTICIPANT", 255, 115);
        g.setColor(Color.BLACK); drawFit(g, p.name, 255, 145, 350, new Font("SansSerif", Font.BOLD, 26));
        g.setColor(Color.DARK_GRAY);
        drawFit(g, e.date.format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy")) + "  |  " + e.time.format(TF)
                + " - " + e.time.plusMinutes(e.durationMin).format(TF), 255, 200, 350, new Font("SansSerif", Font.PLAIN, 17));
        drawFit(g, "Venue: " + e.venue, 255, 230, 350, new Font("SansSerif", Font.PLAIN, 17));
        drawFit(g, "Dept: " + (p.dept == null ? "-" : p.dept), 255, 260, 350, new Font("SansSerif", Font.PLAIN, 15));
        g.setColor(Color.LIGHT_GRAY);
        g.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{8, 8}, 0));
        g.drawLine(625, 15, 625, H - 15);
        int N = 25, cell = 6, ox = 655, oy = 70;
        Random r = new Random(passCode(e, p).hashCode());
        boolean[][] m = new boolean[N][N];
        for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) m[i][j] = r.nextBoolean();
        int[][] fp = {{0, 0}, {N - 7, 0}, {0, N - 7}};
        for (int[] f : fp)
            for (int i = -1; i < 8; i++) for (int j = -1; j < 8; j++) {
                int x = f[0] + i, y = f[1] + j; if (x < 0 || y < 0 || x >= N || y >= N) continue;
                boolean edge = i == 0 || i == 6 || j == 0 || j == 6, core = i >= 2 && i <= 4 && j >= 2 && j <= 4;
                m[x][y] = i >= 0 && i < 7 && j >= 0 && j < 7 && (edge || core);
            }
        g.setColor(Color.BLACK);
        for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) if (m[i][j]) g.fillRect(ox + i * cell, oy + j * cell, cell, cell);
        g.setColor(Color.GRAY); drawCenter(g, "GATE CODE", ox + N * cell / 2, oy + N * cell + 24, 150, new Font("SansSerif", Font.PLAIN, 12));
        g.dispose(); return img;
    }
    static BufferedImage renderCert(EventItem e, Person p, String org, String signer) {
        int W = 1100, H = 780;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Color gold = new Color(184, 134, 11);
        g.setColor(new Color(253, 250, 240)); g.fillRect(0, 0, W, H);
        g.setColor(gold); g.setStroke(new BasicStroke(14)); g.drawRect(20, 20, W - 40, H - 40);
        g.setColor(DARK); g.setStroke(new BasicStroke(2)); g.drawRect(42, 42, W - 84, H - 84);
        int cx = W / 2;
        g.setColor(DARK); drawCenter(g, (org.isBlank() ? "SMART EVENTS ORGANIZATION" : org.toUpperCase()), cx, 120, 800, new Font("SansSerif", Font.BOLD, 24));
        g.setColor(gold); drawCenter(g, "CERTIFICATE", cx, 215, 800, new Font("Serif", Font.BOLD, 70));
        g.setColor(DARK); drawCenter(g, "OF PARTICIPATION", cx, 262, 800, new Font("Serif", Font.PLAIN, 28));
        g.setColor(Color.DARK_GRAY); drawCenter(g, "This certificate is proudly presented to", cx, 340, 800, new Font("Serif", Font.ITALIC, 22));
        g.setColor(Color.BLACK); drawCenter(g, p.name, cx, 425, 850, new Font("Serif", Font.BOLD | Font.ITALIC, 56));
        g.setColor(gold); g.setStroke(new BasicStroke(2)); g.drawLine(cx - 300, 445, cx + 300, 445);
        g.setColor(Color.DARK_GRAY); drawCenter(g, "for actively participating in", cx, 495, 800, new Font("Serif", Font.ITALIC, 22));
        g.setColor(DARK); drawCenter(g, e.name, cx, 548, 850, new Font("Serif", Font.BOLD, 36));
        g.setColor(Color.DARK_GRAY);
        drawCenter(g, "held on " + e.date.format(DateTimeFormatter.ofPattern("dd MMMM yyyy")) + " at " + e.venue, cx, 592, 850, new Font("Serif", Font.PLAIN, 21));
        g.setPaint(new GradientPaint(cx - 50, 625, new Color(250, 204, 21), cx + 50, 725, gold));
        g.fillOval(cx - 50, 630, 100, 100);
        g.setColor(Color.WHITE); g.setStroke(new BasicStroke(3)); g.drawOval(cx - 40, 640, 80, 80);
        drawCenter(g, "VERIFIED", cx, 686, 70, new Font("SansSerif", Font.BOLD, 14));
        g.setColor(Color.BLACK); g.setStroke(new BasicStroke(1.5f));
        g.drawLine(170, 695, 420, 695); g.drawLine(W - 420, 695, W - 170, 695);
        drawCenter(g, signer.isBlank() ? "Event Director" : signer, 295, 720, 240, new Font("Serif", Font.BOLD, 18));
        drawCenter(g, "Organizer", 295, 742, 240, new Font("Serif", Font.PLAIN, 14));
        drawCenter(g, LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy")), W - 295, 720, 240, new Font("Serif", Font.BOLD, 18));
        drawCenter(g, "Date of Issue", W - 295, 742, 240, new Font("Serif", Font.PLAIN, 14));
        g.setColor(Color.GRAY); drawCenter(g, "Certificate No: CERT-" + passCode(e, p), cx, 765, 600, new Font("Monospaced", Font.PLAIN, 12));
        g.dispose(); return img;
    }

    // ======================= CUSTOM COMPONENTS =======================
    static class ImagePreview extends JPanel {
        BufferedImage img; String hint = "Nothing to preview";
        ImagePreview() { setBackground(new Color(226, 232, 240)); }
        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (img == null) { g.setFont(F); g.setColor(Color.GRAY); g.drawString(hint, 30, 40); g.dispose(); return; }
            double s = Math.min((getWidth() - 20.0) / img.getWidth(), (getHeight() - 20.0) / img.getHeight());
            int w = (int) (img.getWidth() * s), h = (int) (img.getHeight() * s);
            g.drawImage(img, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            g.dispose();
        }
    }
    static class BarChart extends JPanel {
        String title, na = "", nb = ""; List<String> labels = new ArrayList<>();
        List<Double> a = new ArrayList<>(), b = null; Color ca = PRIMARY, cb = new Color(209, 213, 219);
        BarChart(String t) { title = t; setBackground(Color.WHITE); setBorder(BorderFactory.createLineBorder(BORDER)); }
        void set(List<String> l, List<Double> a, List<Double> b) { labels = l; this.a = a; this.b = b; repaint(); }
        static String fmt(double v) { return v == Math.floor(v) ? "" + (long) v : String.format("%.1f", v); }
        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setFont(FB); g.setColor(DARK); g.drawString(title, 12, 22);
            if (labels.isEmpty()) { g.setFont(F); g.setColor(Color.GRAY); g.drawString("No data yet", w / 2 - 35, h / 2); g.dispose(); return; }
            int L = 50, R = w - 14, T = 70, B = h - 40;
            double max = 1; for (double v : a) max = Math.max(max, v);
            if (b != null) for (double v : b) max = Math.max(max, v);
            double p10 = Math.pow(10, Math.floor(Math.log10(max))), rr = max / p10;
            max = (rr <= 1 ? 1 : rr <= 2 ? 2 : rr <= 5 ? 5 : 10) * p10;
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            for (int i = 0; i <= 4; i++) {
                int y = B - (B - T) * i / 4; g.setColor(new Color(229, 231, 235)); g.drawLine(L, y, R, y);
                g.setColor(Color.GRAY); g.drawString(fmt(max * i / 4), 6, y + 4);
            }
            int n = labels.size(), series = (b != null) ? 2 : 1; double gw = (double) (R - L) / n;
            int bw = (int) Math.min(36, gw / (series + 1));
            for (int i = 0; i < n; i++) {
                double cx = L + gw * i + gw / 2;
                for (int s = 0; s < series; s++) {
                    double val = s == 0 ? a.get(i) : b.get(i);
                    int bh = (int) ((B - T) * val / max), x = (int) (cx - bw * series / 2.0 + s * bw);
                    g.setColor(s == 0 ? ca : cb); g.fillRoundRect(x, B - bh, bw - 2, bh, 6, 6);
                    g.setColor(Color.DARK_GRAY); g.drawString(fmt(val), x, B - bh - 3);
                }
                String lb = trunc(labels.get(i), 11); g.setColor(Color.DARK_GRAY);
                g.drawString(lb, (int) cx - g.getFontMetrics().stringWidth(lb) / 2, B + 16);
            }
            g.setColor(ca); g.fillRect(12, 34, 10, 10); g.setColor(Color.DARK_GRAY); g.drawString(na, 26, 43);
            if (b != null) { g.setColor(cb); g.fillRect(120, 34, 10, 10); g.setColor(Color.DARK_GRAY); g.drawString(nb, 134, 43); }
            g.dispose();
        }
    }
    static class PieChart extends JPanel {
        String title; Map<String, Integer> data = new LinkedHashMap<>();
        static final Color[] PAL = {PRIMARY, ACCENT, WARN, DANGER, new Color(14, 165, 233), new Color(168, 85, 247), new Color(107, 114, 128)};
        PieChart(String t) { title = t; setBackground(Color.WHITE); setBorder(BorderFactory.createLineBorder(BORDER)); }
        void set(Map<String, Integer> d) { data = d; repaint(); }
        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setFont(FB); g.setColor(DARK); g.drawString(title, 12, 22);
            int total = data.values().stream().mapToInt(Integer::intValue).sum();
            if (total == 0) { g.setFont(F); g.setColor(Color.GRAY); g.drawString("No data yet", getWidth() / 2 - 35, getHeight() / 2); g.dispose(); return; }
            int d = Math.max(40, Math.min(getWidth() - 190, getHeight() - 70)); double start = 90; int i = 0, ly = 60;
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            for (Map.Entry<String, Integer> en : data.entrySet()) {
                double ext = -360.0 * en.getValue() / total; g.setColor(PAL[i % PAL.length]);
                g.fill(new Arc2D.Double(20, 45, d, d, start, ext, Arc2D.PIE)); start += ext;
                g.fillRect(d + 40, ly - 10, 12, 12); g.setColor(Color.DARK_GRAY);
                g.drawString(en.getKey() + " (" + en.getValue() + ")", d + 58, ly); ly += 22; i++;
            }
            g.dispose();
        }
    }

    // ======================= MAIN FRAME =======================
    public SmartEventManager() {
        super("Smart Event Management System");
        setDefaultCloseOperation(EXIT_ON_CLOSE); setSize(1340, 840); setLocationRelativeTo(null);
        getContentPane().setBackground(BG);
        JPanel side = new JPanel() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setPaint(new GradientPaint(0, 0, DARK, 0, getHeight(), new Color(49, 46, 129))); g.fillRect(0, 0, getWidth(), getHeight()); g.dispose();
            }
        };
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS)); side.setPreferredSize(new Dimension(230, 0));
        JLabel logo = new JLabel("SMART EVENTS"); logo.setFont(new Font("SansSerif", Font.BOLD, 22)); logo.setForeground(Color.WHITE);
        logo.setBorder(BorderFactory.createEmptyBorder(28, 28, 2, 0)); side.add(logo);
        JLabel sub = new JLabel("Management System"); sub.setFont(new Font("SansSerif", Font.PLAIN, 12)); sub.setForeground(new Color(165, 180, 252));
        sub.setBorder(BorderFactory.createEmptyBorder(0, 28, 24, 0)); side.add(sub);
        CardLayout cl = new CardLayout(); JPanel content = new JPanel(cl); content.setBackground(BG);
        String[] names = {"Dashboard", "Events", "Participants", "Passes", "Certificates", "Analysis", "AI Assistant"};
        JPanel[] ps = {new Dashboard(), new EventPanel(), new ParticipantPanel(), new DocPanel(false), new DocPanel(true), new AnalysisPanel(), new AIPanel()};
        NavButton[] nav = new NavButton[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            content.add(ps[i], names[i]);
            if (ps[i] instanceof Refreshable) panels.add((Refreshable) ps[i]);
            NavButton b = new NavButton(names[i]);
            b.addActionListener(e -> {
                cl.show(content, names[idx]);
                for (NavButton x : nav) x.setSel(false);
                b.setSel(true);
                if (ps[idx] instanceof Refreshable) ((Refreshable) ps[idx]).refresh();
            });
            nav[i] = b; side.add(b);
        }
        nav[0].setSel(true);
        side.add(Box.createVerticalGlue());
        JLabel today = new JLabel(LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy")));
        today.setFont(new Font("SansSerif", Font.PLAIN, 12)); today.setForeground(new Color(165, 180, 252));
        today.setBorder(BorderFactory.createEmptyBorder(0, 28, 20, 0)); side.add(today);
        add(side, BorderLayout.WEST); add(content, BorderLayout.CENTER);
        refreshAllNoSave();
    }
    void refreshAllNoSave() { for (Refreshable r : panels) r.refresh(); }

    // ======================= DASHBOARD =======================
    class Dashboard extends JPanel implements Refreshable {
        JLabel[] vals = new JLabel[5];
        DefaultTableModel m = model("ID", "Event", "Category", "Date", "Time", "Venue", "Registered");
        Dashboard() {
            setLayout(new BorderLayout(12, 12)); setBackground(BG); setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
            JPanel stats = new JPanel(new GridLayout(1, 5, 14, 0)); stats.setOpaque(false);
            String[] t = {"Total Events", "Participants", "Upcoming", "Attendance Rate", "Revenue"};
            Color[] cs = {PRIMARY, ACCENT, WARN, new Color(14, 165, 233), DANGER};
            for (int i = 0; i < 5; i++) {
                Card c = card().accent(cs[i]); c.setPreferredSize(new Dimension(0, 100));
                JLabel tl = new JLabel(t[i]); tl.setFont(F); tl.setForeground(new Color(100, 116, 139));
                vals[i] = new JLabel("0"); vals[i].setFont(new Font("SansSerif", Font.BOLD, 28)); vals[i].setForeground(DARK);
                c.add(tl, BorderLayout.NORTH); c.add(vals[i], BorderLayout.CENTER); stats.add(c);
            }
            JPanel north = new JPanel(new BorderLayout(0, 14)); north.setOpaque(false);
            north.add(header("Dashboard", "Overview of your events, participants and revenue"), BorderLayout.NORTH); north.add(stats, BorderLayout.CENTER); add(north, BorderLayout.NORTH);
            Card c = card(); JLabel h = lbl("Upcoming Events"); h.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
            c.add(h, BorderLayout.NORTH); c.add(sp(table(m)), BorderLayout.CENTER); add(c, BorderLayout.CENTER);
            JPanel s = new JPanel(new FlowLayout(FlowLayout.LEFT)); s.setOpaque(false);
            JButton demo = btn("Load Demo Data", ACCENT), clear = btn("Clear All Data", DANGER);
            demo.addActionListener(e -> {
                if (JOptionPane.showConfirmDialog(this, "Replace current data with demo data?", "Confirm", JOptionPane.YES_NO_OPTION) == 0) { loadDemo(); refreshAll(); }
            });
            clear.addActionListener(e -> {
                if (JOptionPane.showConfirmDialog(this, "Delete ALL data?", "Confirm", JOptionPane.YES_NO_OPTION) == 0) { store = new Store(); refreshAll(); }
            });
            s.add(demo); s.add(clear); add(s, BorderLayout.SOUTH);
        }
        public void refresh() {
            LocalDate today = LocalDate.now(); int up = 0, reg = 0, in = 0; double rev = 0;
            List<EventItem> upcoming = new ArrayList<>();
            for (EventItem e : store.events) {
                int r = regCount(e.id); rev += r * e.fee;
                if (!e.date.isBefore(today)) { up++; upcoming.add(e); } else { reg += r; in += inCount(e.id); }
            }
            vals[0].setText("" + store.events.size()); vals[1].setText("" + store.participants.size()); vals[2].setText("" + up);
            vals[3].setText(reg == 0 ? "N/A" : String.format("%.0f%%", 100.0 * in / reg)); vals[4].setText("Rs. " + (long) rev);
            upcoming.sort(Comparator.comparing((EventItem e) -> e.date).thenComparing(e -> e.time));
            m.setRowCount(0);
            for (EventItem e : upcoming)
                m.addRow(new Object[]{e.id, e.name, e.category, e.date.format(DF), e.time.format(TF), e.venue, regCount(e.id) + " / " + e.capacity});
        }
    }

    // ======================= EVENTS =======================
    class EventPanel extends JPanel implements Refreshable {
        JTextField tfName = tf(18); JComboBox<String> cbCat = new JComboBox<>(CATS);
        JSpinner spDate = dateSpinner(), spTime = timeSpinner();
        JSpinner spDur = new JSpinner(new SpinnerNumberModel(120, 15, 1440, 15));
        JComboBox<String> cbVenue = new JComboBox<>(VENUES.keySet().toArray(new String[0]));
        JSpinner spCap = new JSpinner(new SpinnerNumberModel(100, 1, 10000, 1));
        JSpinner spFee = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 100000.0, 50.0));
        JTextArea taDesc = new JTextArea(3, 18);
        DefaultTableModel m = model("ID", "Name", "Category", "Date", "Time", "Venue", "Capacity", "Registered", "Fee");
        JTable t = table(m);
        EventPanel() {
            setLayout(new BorderLayout(12, 12)); setBackground(BG); setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
            add(header("Event Management", "Create events with date, time and venue; clashes are detected automatically"), BorderLayout.NORTH);
            setTime(spTime, LocalTime.of(10, 0)); taDesc.setLineWrap(true); taDesc.setWrapStyleWord(true); taDesc.setFont(F);
            cbVenue.addActionListener(e -> spCap.setValue(VENUES.get((String) cbVenue.getSelectedItem())));
            spCap.setValue(VENUES.get((String) cbVenue.getSelectedItem()));
            live(tfName, s -> s.length() >= 3 && s.length() <= 80);
            JPanel form = new JPanel(new GridBagLayout()); form.setOpaque(false);
            addRow(form, 0, "Event Name", tfName); addRow(form, 1, "Category", cbCat); addRow(form, 2, "Date", spDate);
            addRow(form, 3, "Start Time", spTime); addRow(form, 4, "Duration (min)", spDur); addRow(form, 5, "Venue", cbVenue);
            addRow(form, 6, "Capacity", spCap); addRow(form, 7, "Fee (Rs.)", spFee); addRow(form, 8, "Description", sp(taDesc));
            JButton add = btn("Add Event", PRIMARY), upd = btn("Update", WARN), del = btn("Delete", DANGER), clr = btn("Clear", new Color(100, 116, 139));
            JPanel bp = new JPanel(new GridLayout(2, 2, 8, 8)); bp.setOpaque(false); bp.add(add); bp.add(upd); bp.add(del); bp.add(clr);
            Card left = card(); left.setPreferredSize(new Dimension(390, 0)); left.add(form, BorderLayout.CENTER); left.add(bp, BorderLayout.SOUTH);
            add(left, BorderLayout.WEST);
            Card right = card(); right.add(sp(t)); add(right, BorderLayout.CENTER);

            add.addActionListener(e -> {
                EventItem n = new EventItem(); if (!read(n, null)) return;
                EventItem c = conflict(n.venue, n.date, n.time, n.durationMin, null);
                if (c != null) { err(this, "Venue clash with '" + c.name + "' (" + c.time.format(TF) + ", " + c.durationMin + " min)."); return; }
                n.id = "E" + (++store.evCounter); store.events.add(n); clear(); refreshAll();
            });
            upd.addActionListener(e -> {
                String id = selId(t); if (id == null) { err(this, "Select an event in the table first."); return; }
                EventItem old = ev(id);
                EventItem n = new EventItem(); if (!read(n, old)) return; n.id = id;
                EventItem c = conflict(n.venue, n.date, n.time, n.durationMin, id);
                if (c != null) { err(this, "Venue clash with '" + c.name + "'."); return; }
                if (n.capacity < regCount(id)) { err(this, "Capacity is lower than current registrations (" + regCount(id) + ")."); return; }
                store.events.set(store.events.indexOf(old), n); refreshAll();
            });
            del.addActionListener(e -> {
                String id = selId(t); if (id == null) { err(this, "Select an event first."); return; }
                if (JOptionPane.showConfirmDialog(this, "Delete event and its participants?", "Confirm", JOptionPane.YES_NO_OPTION) != 0) return;
                store.participants.removeIf(p -> p.eventId.equals(id)); store.events.remove(ev(id)); clear(); refreshAll();
            });
            clr.addActionListener(e -> clear());
            t.getSelectionModel().addListSelectionListener(e -> {
                if (e.getValueIsAdjusting()) return; EventItem x = ev(selId(t) == null ? "" : selId(t)); if (x == null) return;
                tfName.setText(x.name); cbCat.setSelectedItem(x.category); setDate(spDate, x.date); setTime(spTime, x.time);
                spDur.setValue(x.durationMin); cbVenue.setSelectedItem(x.venue); spCap.setValue(x.capacity);
                spFee.setValue(x.fee); taDesc.setText(x.description);
            });
        }
        boolean read(EventItem n, EventItem old) {
            String nm = tfName.getText().trim();
            if (nm.isEmpty()) { err(this, "Event name is required."); return false; }
            if (nm.length() < 3 || nm.length() > 80) { err(this, "Event name must be 3 to 80 characters."); return false; }
            if (!commit(spDate)) { err(this, "Invalid date. Use the format yyyy-MM-dd (e.g. 2026-11-25) with a real calendar date."); return false; }
            if (!commit(spTime)) { err(this, "Invalid time. Use 24-hour format HH:mm (e.g. 14:30)."); return false; }
            n.name = nm; n.category = (String) cbCat.getSelectedItem(); n.date = toDate(spDate); n.time = toTime(spTime);
            n.durationMin = (Integer) spDur.getValue(); n.venue = (String) cbVenue.getSelectedItem(); n.capacity = (Integer) spCap.getValue();
            n.fee = ((Number) spFee.getValue()).doubleValue(); n.description = taDesc.getText().trim();
            LocalDate today = LocalDate.now();
            boolean changed = old == null || !n.date.equals(old.date) || !n.time.equals(old.time);
            if (changed && n.date.isBefore(today)) { err(this, "Event date cannot be in the past (today is " + today.format(DF) + ")."); return false; }
            if (changed && n.date.equals(today) && n.time.isBefore(LocalTime.now())) { err(this, "The start time has already passed today."); return false; }
            if (n.date.isAfter(today.plusYears(3))) { err(this, "Event date is too far ahead (maximum 3 years)."); return false; }
            if (mins(n.time) + n.durationMin > 24 * 60) { err(this, "Event must end before midnight."); return false; }
            if (n.fee < 0) { err(this, "Fee cannot be negative."); return false; }
            return true;
        }
        void clear() { tfName.setText(""); taDesc.setText(""); t.clearSelection(); }
        public void refresh() {
            m.setRowCount(0);
            for (EventItem e : store.events)
                m.addRow(new Object[]{e.id, e.name, e.category, e.date.format(DF), e.time.format(TF), e.venue, e.capacity, regCount(e.id), e.fee});
        }
    }

    // ======================= PARTICIPANTS =======================
    class ParticipantPanel extends JPanel implements Refreshable {
        JTextField tfName = tf(16), tfEmail = tf(16), tfPhone = tf(16), tfDept = tf(16), tfSearch = tf(14), tfFeed = tf(16);
        JComboBox<EventItem> cbEvent = new JComboBox<>(); JComboBox<String> cbRole = new JComboBox<>(ROLES);
        JComboBox<Object> cbFilter = new JComboBox<>(); JSpinner spRating = new JSpinner(new SpinnerNumberModel(5, 1, 5, 1));
        DefaultTableModel m = model("ID", "Name", "Email", "Phone", "Dept", "Role", "Event", "Checked-in", "Rating");
        JTable t = table(m); boolean loading;
        ParticipantPanel() {
            setLayout(new BorderLayout(12, 12)); setBackground(BG); setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
            add(header("Participants", "Register attendees, check them in and collect feedback"), BorderLayout.NORTH);
            live(tfName, s -> validName(s)); live(tfEmail, s -> validEmail(s)); live(tfPhone, s -> validPhone(s));
            tfEmail.setToolTipText("e.g. name@example.com"); tfPhone.setToolTipText("7-15 digits, optional + prefix");
            JPanel form = new JPanel(new GridBagLayout()); form.setOpaque(false);
            addRow(form, 0, "Name", tfName); addRow(form, 1, "Email", tfEmail); addRow(form, 2, "Phone", tfPhone);
            addRow(form, 3, "Department", tfDept); addRow(form, 4, "Event", cbEvent); addRow(form, 5, "Role", cbRole);
            JButton reg = btn("Register Participant", PRIMARY); form.add(reg, new GridBagConstraints(0, 6, 2, 1, 1, 0, 10, 2, new Insets(12, 6, 4, 6), 0, 0));
            JPanel fb = new JPanel(new GridBagLayout()); fb.setOpaque(false);
            addRow(fb, 0, "Rating (1-5)", spRating); addRow(fb, 1, "Feedback", tfFeed);
            JButton sf = btn("Save Feedback for Selected", ACCENT); fb.add(sf, new GridBagConstraints(0, 2, 2, 1, 1, 0, 10, 2, new Insets(10, 6, 4, 6), 0, 0));
            JPanel left = new JPanel(); left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS)); left.setOpaque(false); left.setPreferredSize(new Dimension(390, 0));
            Card c1 = card(); c1.add(form); Card c2 = card(); c2.add(fb);
            left.add(c1); left.add(Box.createVerticalStrut(12)); left.add(c2); add(left, BorderLayout.WEST);
            JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4)); tools.setOpaque(false);
            JButton chk = btn("Toggle Check-in", ACCENT), del = btn("Delete", DANGER), csv = btn("Export CSV", WARN);
            tools.add(lbl("Filter:")); tools.add(cbFilter); tools.add(lbl("Search:")); tools.add(tfSearch); tools.add(chk); tools.add(del); tools.add(csv);
            Card right = card(); right.add(tools, BorderLayout.NORTH); right.add(sp(t), BorderLayout.CENTER); add(right, BorderLayout.CENTER);

            reg.addActionListener(e -> {
                EventItem ei = (EventItem) cbEvent.getSelectedItem(); if (ei == null) { err(this, "Create an event first."); return; }
                String n = tfName.getText().trim(), em = tfEmail.getText().trim().toLowerCase(), ph = tfPhone.getText().trim();
                if (n.isEmpty()) { err(this, "Name is required."); return; }
                if (!validName(n)) { err(this, "Name may contain only letters, spaces and . ' - (2 to 60 characters)."); return; }
                if (em.isEmpty()) { err(this, "Email is required."); return; }
                if (!validEmail(em)) { err(this, "Enter a valid email address (e.g. name@example.com)."); return; }
                if (!ph.isEmpty() && !validPhone(ph)) { err(this, "Enter a valid phone number (7 to 15 digits, optional + prefix)."); return; }
                if (ei.date.isBefore(LocalDate.now())) { err(this, "Registration is closed: '" + ei.name + "' took place on " + ei.date.format(DF) + "."); return; }
                if (regCount(ei.id) >= ei.capacity) { err(this, "Event is full (capacity " + ei.capacity + ")."); return; }
                for (Person q : store.participants) if (q.eventId.equals(ei.id) && q.email.equalsIgnoreCase(em)) { err(this, "This email is already registered for this event."); return; }
                Person p = new Person(); p.id = "P" + (++store.pCounter); p.name = n; p.email = em; p.phone = ph; p.dept = tfDept.getText().trim();
                p.eventId = ei.id; p.role = (String) cbRole.getSelectedItem(); store.participants.add(p);
                tfName.setText(""); tfEmail.setText(""); tfPhone.setText(""); tfDept.setText(""); refreshAll();
            });
            chk.addActionListener(e -> {
                Person p = selected(); if (p == null) return; p.checkedIn = !p.checkedIn; refreshAll();
            });
            del.addActionListener(e -> {
                Person p = selected(); if (p == null) return; store.participants.remove(p); refreshAll();
            });
            sf.addActionListener(e -> {
                Person p = selected(); if (p == null) return; p.rating = (Integer) spRating.getValue(); p.feedback = tfFeed.getText().trim(); tfFeed.setText(""); refreshAll();
            });
            csv.addActionListener(e -> exportCsv());
            cbFilter.addActionListener(e -> fillTable());
            tfSearch.getDocument().addDocumentListener(new DL(this::fillTable));
        }
        Person selected() {
            String id = selId(t); if (id == null) { err(this, "Select a participant in the table first."); return null; } return person(id);
        }
        String csvq(String s) { return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\""; }
        void exportCsv() {
            JFileChooser fc = new JFileChooser(); fc.setSelectedFile(new File("participants.csv"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            try (PrintWriter w = new PrintWriter(fc.getSelectedFile(), "UTF-8")) {
                w.println("ID,Name,Email,Phone,Department,Role,EventID,CheckedIn,Rating");
                for (Person p : store.participants)
                    w.println(String.join(",", p.id, csvq(p.name), p.email, csvq(p.phone), csvq(p.dept), p.role, p.eventId, "" + p.checkedIn, "" + p.rating));
                info(this, "Exported to " + fc.getSelectedFile().getAbsolutePath());
            } catch (Exception ex) { err(this, ex.getMessage()); }
        }
        void fillTable() {
            if (loading) return; m.setRowCount(0); Object f = cbFilter.getSelectedItem(); String q = tfSearch.getText().trim().toLowerCase();
            for (Person p : store.participants) {
                if (f instanceof EventItem && !p.eventId.equals(((EventItem) f).id)) continue;
                if (!q.isEmpty() && !(p.name.toLowerCase().contains(q) || p.email.toLowerCase().contains(q))) continue;
                EventItem e = ev(p.eventId);
                m.addRow(new Object[]{p.id, p.name, p.email, p.phone, p.dept, p.role, e == null ? p.eventId : e.name, p.checkedIn ? "Yes" : "No", p.rating == 0 ? "-" : p.rating});
            }
        }
        public void refresh() {
            loading = true;
            Object fsel = cbFilter.getSelectedItem(); Object rsel = cbEvent.getSelectedItem();
            cbEvent.removeAllItems(); cbFilter.removeAllItems(); cbFilter.addItem("All Events");
            for (EventItem e : store.events) { cbEvent.addItem(e); cbFilter.addItem(e); }
            if (rsel instanceof EventItem && ev(((EventItem) rsel).id) != null) cbEvent.setSelectedItem(ev(((EventItem) rsel).id));
            if (fsel instanceof EventItem && ev(((EventItem) fsel).id) != null) cbFilter.setSelectedItem(ev(((EventItem) fsel).id));
            loading = false; fillTable();
        }
    }

    // ======================= PASSES & CERTIFICATES =======================
    class DocPanel extends JPanel implements Refreshable {
        final boolean cert; JComboBox<Person> cb = new JComboBox<>(); ImagePreview pv = new ImagePreview();
        JTextField tfOrg = tf(16), tfSign = tf(14); boolean loading;
        DocPanel(boolean cert) {
            this.cert = cert; setLayout(new BorderLayout(12, 12)); setBackground(BG); setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
            add(header(cert ? "Certificate Generator" : "Entry Pass Generator",
                    cert ? "Issue certificates to checked-in participants" : "Create printable entry passes with role colours and gate code"), BorderLayout.NORTH);
            pv.hint = cert ? "No checked-in participants yet. Check people in under 'Participants'." : "No participants yet.";
            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6)); top.setOpaque(false);
            top.add(lbl(cert ? "Checked-in participant:" : "Participant:")); cb.setPreferredSize(new Dimension(280, 30)); top.add(cb);
            if (cert) { top.add(lbl("Organization:")); top.add(tfOrg); top.add(lbl("Signatory:")); top.add(tfSign); JButton up = btn("Update Preview", WARN); up.addActionListener(e -> update()); top.add(up); }
            Card c = card(); c.add(top, BorderLayout.NORTH); c.add(pv, BorderLayout.CENTER);
            JPanel bot = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6)); bot.setOpaque(false);
            JButton one = btn(cert ? "Save Certificate (PNG)" : "Save Pass (PNG)", PRIMARY), all = btn(cert ? "Save ALL Certificates" : "Save ALL Passes", ACCENT);
            bot.add(one); bot.add(all); c.add(bot, BorderLayout.SOUTH); add(c, BorderLayout.CENTER);
            cb.addActionListener(e -> { if (!loading) update(); });
            one.addActionListener(e -> {
                Person p = (Person) cb.getSelectedItem(); if (p == null) { err(this, "No participant selected."); return; }
                JFileChooser fc = new JFileChooser(); fc.setSelectedFile(new File((cert ? "certificate_" : "pass_") + p.id + ".png"));
                if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
                try { ImageIO.write(render(p), "png", fc.getSelectedFile()); info(this, "Saved: " + fc.getSelectedFile().getAbsolutePath()); }
                catch (Exception ex) { err(this, ex.getMessage()); }
            });
            all.addActionListener(e -> {
                List<Person> l = eligible(); if (l.isEmpty()) { err(this, "Nobody eligible."); return; }
                JFileChooser fc = new JFileChooser(); fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
                try {
                    for (Person p : l) ImageIO.write(render(p), "png", new File(fc.getSelectedFile(), (cert ? "certificate_" : "pass_") + p.id + ".png"));
                    info(this, l.size() + " file(s) saved to " + fc.getSelectedFile().getAbsolutePath());
                } catch (Exception ex) { err(this, ex.getMessage()); }
            });
        }
        BufferedImage render(Person p) {
            EventItem e = ev(p.eventId);
            return cert ? renderCert(e, p, tfOrg.getText().trim(), tfSign.getText().trim()) : renderPass(e, p);
        }
        List<Person> eligible() {
            return store.participants.stream().filter(p -> ev(p.eventId) != null && (!cert || p.checkedIn)).collect(Collectors.toList());
        }
        void update() { Person p = (Person) cb.getSelectedItem(); pv.img = p == null ? null : render(p); pv.repaint(); }
        public void refresh() {
            loading = true; Person sel = (Person) cb.getSelectedItem(); String sid = sel == null ? null : sel.id; cb.removeAllItems();
            for (Person p : eligible()) { cb.addItem(p); if (p.id.equals(sid)) cb.setSelectedItem(p); }
            loading = false; update();
        }
    }

    // ======================= ANALYSIS =======================
    class AnalysisPanel extends JPanel implements Refreshable {
        BarChart c1 = new BarChart("Registrations vs Capacity"), c2 = new BarChart("Attendance % (past events)"), c3 = new BarChart("Revenue per Event (Rs.)");
        PieChart c4 = new PieChart("Participants by Category"); JTextArea insights = new JTextArea(6, 40);
        AnalysisPanel() {
            setLayout(new BorderLayout(12, 12)); setBackground(BG); setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
            add(header("Analysis & Insights", "Charts and automatic observations from your data"), BorderLayout.NORTH);
            c1.na = "Registered"; c1.nb = "Capacity"; c1.ca = PRIMARY; c2.na = "Attendance %"; c2.ca = ACCENT; c3.na = "Revenue"; c3.ca = WARN;
            insights.setEditable(false); insights.setFont(F); insights.setLineWrap(true); insights.setWrapStyleWord(true);
            Card top = card(); top.add(sp(insights)); top.setPreferredSize(new Dimension(0, 160));
            JPanel charts = new JPanel(new GridLayout(2, 2, 12, 12)); charts.setOpaque(false);
            charts.add(c1); charts.add(c2); charts.add(c3); charts.add(c4);
            JPanel center = new JPanel(new BorderLayout(0, 12)); center.setOpaque(false); center.add(top, BorderLayout.NORTH); center.add(charts, BorderLayout.CENTER);
            add(center, BorderLayout.CENTER);
        }
        public void refresh() {
            List<String> l1 = new ArrayList<>(), l2 = new ArrayList<>(), l3 = new ArrayList<>();
            List<Double> reg = new ArrayList<>(), cap = new ArrayList<>(), att = new ArrayList<>(), rev = new ArrayList<>();
            Map<String, Integer> cat = new LinkedHashMap<>(); LocalDate today = LocalDate.now();
            for (EventItem e : store.events) {
                int r = regCount(e.id); l1.add(e.name); reg.add((double) r); cap.add((double) e.capacity);
                l3.add(e.name); rev.add(r * e.fee); cat.merge(e.category, r, Integer::sum);
                if (e.date.isBefore(today) && r > 0) { l2.add(e.name); att.add(100.0 * inCount(e.id) / r); }
            }
            cat.values().removeIf(v -> v == 0);
            c1.set(l1, reg, cap); c2.set(l2, att, null); c3.set(l3, rev, null); c4.set(cat);
            StringBuilder sb = new StringBuilder();
            if (store.events.isEmpty()) sb.append("No data yet. Add events/participants or press 'Load Demo Data' on the Dashboard.");
            else {
                EventItem top = store.events.stream().max(Comparator.comparingDouble(e -> (double) regCount(e.id) / e.capacity)).get();
                sb.append(String.format("* Most popular event: %s (%.0f%% full)%n", top.name, 100.0 * regCount(top.id) / top.capacity));
                double avgFill = store.events.stream().mapToDouble(e -> (double) regCount(e.id) / e.capacity).average().orElse(0);
                sb.append(String.format("* Average fill rate: %.0f%%%n", avgFill * 100));
                OptionalDouble ar = store.participants.stream().filter(p -> p.rating > 0).mapToInt(p -> p.rating).average();
                sb.append(ar.isPresent() ? String.format("* Average participant rating: %.2f / 5%n", ar.getAsDouble()) : "* No ratings collected yet.\n");
                cat.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(en -> sb.append("* Strongest category: " + en.getKey() + " (" + en.getValue() + " registrations)\n"));
                for (EventItem e : store.events)
                    if (!e.date.isBefore(today) && (double) regCount(e.id) / e.capacity < 0.4)
                        sb.append("* Needs promotion: " + e.name + " is only " + (int) (100.0 * regCount(e.id) / e.capacity) + "% full (" + e.date + ").\n");
                Map<String, Long> vc = store.events.stream().collect(Collectors.groupingBy(e -> e.venue, Collectors.counting()));
                vc.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(en -> sb.append("* Busiest venue: " + en.getKey() + " (" + en.getValue() + " events)\n"));
            }
            insights.setText(sb.toString()); insights.setCaretPosition(0);
        }
    }

    // ======================= AI MODULE =======================
    static final Set<String> POS = new HashSet<>(Arrays.asList("great", "good", "excellent", "amazing", "awesome", "loved", "love", "nice", "helpful", "fantastic", "best", "informative", "engaging", "enjoyed", "perfect", "smooth", "wonderful", "fun", "inspiring"));
    static final Set<String> NEG = new HashSet<>(Arrays.asList("bad", "poor", "boring", "late", "delay", "delayed", "worst", "crowded", "disorganized", "confusing", "slow", "noisy", "terrible", "awful", "waste", "disappointing", "rude", "cold", "expensive", "long"));
    static final Set<String> STOP = new HashSet<>(Arrays.asList("the", "and", "was", "were", "are", "for", "with", "that", "this", "very", "had", "has", "but", "not", "you", "its", "our", "from", "have", "too", "all", "can", "event"));

    class AIPanel extends JPanel implements Refreshable {
        JComboBox<EventItem> cbPred = new JComboBox<>(), cbFeed = new JComboBox<>();
        JTextArea outPred = out(), outSched = out(), outWrite = out(), outFeed = out(), log = out();
        JPasswordField pfKey = new JPasswordField(22); JTextField tfIn = tf(40); JLabel status = new JLabel(" ");
        JComboBox<String> cbModel = new JComboBox<>(MODELS);
        JButton send = btn("Send", PRIMARY), test = btn("Test Connection", WARN);
        final List<String[]> hist = new ArrayList<>();
        JTextArea out() { JTextArea a = new JTextArea(); a.setEditable(false); a.setFont(new Font("SansSerif", Font.PLAIN, 14)); a.setLineWrap(true); a.setWrapStyleWord(true); a.setMargin(new Insets(8, 8, 8, 8)); return a; }
        JPanel tab(JPanel top, JTextArea a) { JPanel p = new JPanel(new BorderLayout(8, 8)); p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); p.setOpaque(false); top.setOpaque(false); p.add(top, BorderLayout.NORTH); p.add(sp(a), BorderLayout.CENTER); return p; }

        AIPanel() {
            setLayout(new BorderLayout(12, 12)); setBackground(BG); setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
            add(header("AI Assistant", "Turnout prediction, smart scheduling, copywriting, sentiment analysis and chat"), BorderLayout.NORTH);
            JTabbedPane tabs = new JTabbedPane(); tabs.setFont(FB);
            JPanel t1 = new JPanel(new FlowLayout(FlowLayout.LEFT)); t1.add(lbl("Event:")); cbPred.setPreferredSize(new Dimension(300, 30)); t1.add(cbPred);
            JButton b1 = btn("Predict Turnout", PRIMARY); t1.add(b1);
            b1.addActionListener(e -> { EventItem x = (EventItem) cbPred.getSelectedItem(); outPred.setText(x == null ? "Create an event first." : predict(x)); });
            tabs.addTab("Turnout Predictor", tab(t1, outPred));
            JComboBox<String> cbC = new JComboBox<>(CATS); JSpinner spA = new JSpinner(new SpinnerNumberModel(100, 5, 2000, 5)), spD = new JSpinner(new SpinnerNumberModel(120, 30, 600, 15)), spH = new JSpinner(new SpinnerNumberModel(30, 3, 120, 1));
            JPanel t2 = new JPanel(new FlowLayout(FlowLayout.LEFT)); t2.add(lbl("Category:")); t2.add(cbC); t2.add(lbl("Expected people:")); t2.add(spA);
            t2.add(lbl("Duration (min):")); t2.add(spD); t2.add(lbl("Look ahead (days):")); t2.add(spH);
            JButton b2 = btn("Suggest Best Slots", PRIMARY); t2.add(b2);
            b2.addActionListener(e -> outSched.setText(suggest((String) cbC.getSelectedItem(), (Integer) spA.getValue(), (Integer) spD.getValue(), (Integer) spH.getValue())));
            tabs.addTab("Smart Scheduler", tab(t2, outSched));
            JTextField tfN = tf(16), tfAud = tf(12); tfAud.setText("students"); JComboBox<String> cbC2 = new JComboBox<>(CATS), cbTone = new JComboBox<>(new String[]{"Professional", "Fun", "Inspiring"});
            JPanel t3 = new JPanel(new FlowLayout(FlowLayout.LEFT)); t3.add(lbl("Event name:")); t3.add(tfN); t3.add(lbl("Category:")); t3.add(cbC2);
            t3.add(lbl("Audience:")); t3.add(tfAud); t3.add(lbl("Tone:")); t3.add(cbTone); JButton b3 = btn("Generate Copy", PRIMARY); t3.add(b3);
            b3.addActionListener(e -> outWrite.setText(write(tfN.getText().trim().isEmpty() ? "Your Event" : tfN.getText().trim(), (String) cbC2.getSelectedItem(), tfAud.getText().trim(), (String) cbTone.getSelectedItem())));
            tabs.addTab("Description Writer", tab(t3, outWrite));
            JPanel t4 = new JPanel(new FlowLayout(FlowLayout.LEFT)); t4.add(lbl("Event:")); cbFeed.setPreferredSize(new Dimension(300, 30)); t4.add(cbFeed);
            JButton b4 = btn("Analyze Feedback", PRIMARY); t4.add(b4);
            b4.addActionListener(e -> { EventItem x = (EventItem) cbFeed.getSelectedItem(); outFeed.setText(x == null ? "Create an event first." : sentiment(x)); });
            tabs.addTab("Feedback Sentiment", tab(t4, outFeed));
            // Chat
            JPanel t5 = new JPanel(new FlowLayout(FlowLayout.LEFT)); t5.add(lbl("Claude API key:"));
            String env = System.getenv("ANTHROPIC_API_KEY"); if (env != null) pfKey.setText(env); pfKey.setToolTipText("Leave empty to use the built-in offline assistant");
            t5.add(pfKey); t5.add(lbl("Model:")); cbModel.setEditable(true); cbModel.setPreferredSize(new Dimension(190, 30)); t5.add(cbModel); t5.add(test);
            JPanel chat = tab(t5, log); JPanel in = new JPanel(new BorderLayout(6, 6)); in.setOpaque(false); in.add(tfIn, BorderLayout.CENTER); in.add(send, BorderLayout.EAST); in.add(status, BorderLayout.SOUTH);
            chat.add(in, BorderLayout.SOUTH); tabs.addTab("Chat Assistant", chat);
            send.addActionListener(e -> sendChat()); tfIn.addActionListener(e -> sendChat()); test.addActionListener(e -> testApi());
            log.setText("Assistant: Hi! Ask me about your events, e.g. 'upcoming events', 'attendance rate', 'free venues tomorrow'.\n"
                    + "Leave the API key empty for the built-in offline assistant (type 'help'), or paste a Claude API key for open-ended answers.\n\n");
            Card c = card(); c.add(tabs); add(c, BorderLayout.CENTER);
        }
        public void refresh() {
            for (JComboBox<EventItem> cb : List.of(cbPred, cbFeed)) {
                EventItem s = (EventItem) cb.getSelectedItem(); cb.removeAllItems();
                for (EventItem e : store.events) cb.addItem(e);
                if (s != null && ev(s.id) != null) cb.setSelectedItem(ev(s.id));
            }
        }

        // ---- Turnout prediction ----
        String predict(EventItem e) {
            int reg = regCount(e.id); if (reg == 0) return "No registrations yet for this event, so there is nothing to predict from.";
            double rate = 0.72; List<String> why = new ArrayList<>();
            double cat = switch (e.category) { case "Technical" -> 0.05; case "Workshop" -> 0.06; case "Cultural" -> 0.03; case "Seminar" -> -0.04; case "Business" -> -0.02; default -> 0.0; };
            rate += cat; if (cat != 0) why.add(String.format("category '%s' adjusts show-up by %+.0f%%", e.category, cat * 100));
            boolean weekend = e.date.getDayOfWeek() == DayOfWeek.SATURDAY || e.date.getDayOfWeek() == DayOfWeek.SUNDAY;
            boolean leisure = e.category.equals("Cultural") || e.category.equals("Social") || e.category.equals("Sports");
            if (weekend) { double d = leisure ? 0.04 : -0.06; rate += d; why.add(String.format("weekend date %+.0f%%", d * 100)); }
            int h = e.time.getHour();
            if (h < 9) { rate -= 0.08; why.add("very early start -8%"); } else if (h >= 18) { rate -= 0.04; why.add("evening start -4%"); } else if (h >= 10 && h <= 15) { rate += 0.02; why.add("prime-time slot +2%"); }
            if (e.fee > 0) { rate += 0.05; why.add("paid ticket commitment +5%"); }
            int hr = 0, hi = 0;
            for (EventItem o : store.events) if (!o.id.equals(e.id) && o.category.equals(e.category) && o.date.isBefore(LocalDate.now())) { hr += regCount(o.id); hi += inCount(o.id); }
            if (hr >= 20) { double hist = (double) hi / hr; rate = 0.5 * rate + 0.5 * hist; why.add(String.format("blended with your past '%s' events (%.0f%% actual attendance)", e.category, hist * 100)); }
            rate = Math.max(0.35, Math.min(0.97, rate));
            int expected = (int) Math.round(reg * rate), walk = e.fee == 0 ? (int) Math.round(reg * 0.06) : 0, total = expected + walk;
            StringBuilder s = new StringBuilder("PREDICTION for " + e.name + "\n\n");
            s.append(String.format("Registered: %d / %d%nPredicted show-up rate: %.0f%%%nExpected attendees: %d (+ about %d walk-ins) = %d%n%n", reg, e.capacity, rate * 100, expected, walk, total));
            s.append("Why: ").append(String.join("; ", why)).append(".\n\nRecommendations:\n");
            s.append("- Catering / kits: prepare for about ").append((int) Math.ceil(total * 1.1)).append(" people (10% buffer).\n");
            s.append("- Volunteers needed: about ").append(Math.max(2, (int) Math.ceil(total / 40.0))).append(" (1 per 40 attendees).\n");
            s.append("- Certificates to print: about ").append(total).append(".\n");
            if (total > e.capacity) s.append("- WARNING: expected crowd exceeds venue capacity. Consider a bigger venue.\n");
            if (total < e.capacity * 0.4) s.append("- Low projected turnout: send reminders and promote on social media.\n");
            s.append("\n(Heuristic model, not a guarantee; it improves as you record real check-ins.)");
            return s.toString();
        }

        // ---- Smart scheduler ----
        class Slot { LocalDate d; LocalTime t; String v; double s; }
        String suggest(String cat, int people, int dur, int horizon) {
            boolean leisure = cat.equals("Cultural") || cat.equals("Social") || cat.equals("Sports");
            LocalTime[] times = leisure ? new LocalTime[]{LocalTime.of(10, 0), LocalTime.of(14, 0), LocalTime.of(17, 0), LocalTime.of(18, 30)}
                    : new LocalTime[]{LocalTime.of(9, 30), LocalTime.of(11, 0), LocalTime.of(14, 0), LocalTime.of(15, 30)};
            List<Slot> c = new ArrayList<>();
            for (int day = 1; day <= horizon; day++) {
                LocalDate d = LocalDate.now().plusDays(day); boolean we = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
                long sameDay = store.events.stream().filter(e -> e.date.equals(d)).count();
                long sameCat = store.events.stream().filter(e -> e.date.equals(d) && e.category.equals(cat)).count();
                for (LocalTime t : times) {
                    if (mins(t) + dur > 21 * 60) continue;
                    for (Map.Entry<String, Integer> v : VENUES.entrySet()) {
                        if (v.getValue() < people * 1.1) continue;
                        if (conflict(v.getKey(), d, t, dur, null) != null) continue;
                        Slot s = new Slot(); s.d = d; s.t = t; s.v = v.getKey();
                        s.s = 100 - ((v.getValue() - people * 1.2) / v.getValue()) * 40 + ((leisure == we) ? 15 : 0) - sameDay * 6 - sameCat * 10 - day * 0.6;
                        c.add(s);
                    }
                }
            }
            if (c.isEmpty()) return "No free slot found. Try a longer look-ahead, shorter duration or fewer people.";
            c.sort((x, y) -> Double.compare(y.s, x.s));
            StringBuilder sb = new StringBuilder("TOP SLOTS for a " + cat + " event (" + people + " people, " + dur + " min)\n\n"); int n = 0;
            for (Slot s : c) {
                if (n++ >= 5) break;
                sb.append(String.format("%d. %s (%s) at %s - %s  [fit score %.0f]%n", n, s.d.format(DF), s.d.getDayOfWeek().toString().substring(0, 3), s.t.format(TF), s.v, s.s));
            }
            sb.append("\nScore considers room size fit, weekday/weekend suitability, clashes with other events, and lead time.");
            return sb.toString();
        }

        // ---- Description writer ----
        String write(String name, String cat, String aud, String tone) {
            Random r = new Random();
            Map<String, String[]> hl = new HashMap<>();
            hl.put("Technical", new String[]{"hands-on demos", "expert talks", "live problem solving"});
            hl.put("Cultural", new String[]{"vibrant performances", "local talent", "unforgettable evenings"});
            hl.put("Sports", new String[]{"thrilling matches", "team spirit", "fitness challenges"});
            hl.put("Workshop", new String[]{"practical exercises", "guided mentorship", "take-home skills"});
            hl.put("Seminar", new String[]{"keynote insights", "Q&A with experts", "fresh perspectives"});
            hl.put("Business", new String[]{"networking sessions", "industry leaders", "growth strategies"});
            hl.put("Social", new String[]{"great company", "fun activities", "new friendships"});
            String[] h = hl.get(cat); String a = aud.isEmpty() ? "everyone" : aud;
            String[] taglines = switch (tone) {
                case "Fun" -> new String[]{name + ": Come for the fun, stay for the memories!", "Don't just hear about " + name + " - be there!"};
                case "Inspiring" -> new String[]{name + ": Where ideas become action.", "Learn. Grow. Lead. Join " + name + "."};
                default -> new String[]{name + ": An Event Not To Be Missed.", "Join us for " + name + " - " + cat + " at its best."};
            };
            String tag = taglines[r.nextInt(taglines.length)];
            String desc = switch (tone) {
                case "Fun" -> "Get ready for " + name + "! Expect " + h[0] + ", " + h[1] + " and plenty of " + h[2] + ". Bring your friends - this one is made for " + a + " who love a great time.";
                case "Inspiring" -> name + " brings together " + a + " to explore what is possible. Through " + h[0] + " and " + h[1] + ", you will leave with " + h[2] + " and the confidence to take the next step.";
                default -> name + " is a " + cat.toLowerCase() + " event designed for " + a + ". The programme features " + h[0] + ", " + h[1] + " and " + h[2] + ". Registration is open; seats are limited.";
            };
            String post = "NEW EVENT: " + name + "\n" + desc.split("\\. ")[0] + ".\nRegister now! #" + name.replaceAll("[^A-Za-z0-9]", "") + " #" + cat + "Event #SmartEvents";
            return "TAGLINE\n" + tag + "\n\nDESCRIPTION\n" + desc + "\n\nSOCIAL POST\n" + post;
        }

        // ---- Feedback sentiment ----
        String sentiment(EventItem e) {
            int n = 0, pos = 0, neg = 0, neu = 0, rated = 0; double sum = 0; Map<String, Integer> freq = new HashMap<>();
            for (Person p : partsOf(e.id)) {
                if (p.rating > 0) { sum += p.rating; rated++; }
                if (p.feedback == null || p.feedback.isBlank()) continue; n++; int s = 0;
                for (String w : p.feedback.toLowerCase().split("[^a-z]+")) {
                    if (w.length() < 3) continue; if (POS.contains(w)) s++; if (NEG.contains(w)) s--; if (!STOP.contains(w)) freq.merge(w, 1, Integer::sum);
                }
                if (s > 0) pos++; else if (s < 0) neg++; else neu++;
            }
            if (n == 0 && rated == 0) return "No feedback recorded for this event yet. Add ratings/feedback in the Participants tab.";
            StringBuilder sb = new StringBuilder("FEEDBACK ANALYSIS: " + e.name + "\n\n");
            if (rated > 0) sb.append(String.format("Average rating: %.2f / 5 (%d ratings)%n", sum / rated, rated));
            if (n > 0) {
                sb.append(String.format("Comments analysed: %d%nPositive: %d (%.0f%%)   Neutral: %d (%.0f%%)   Negative: %d (%.0f%%)%n", n, pos, 100.0 * pos / n, neu, 100.0 * neu / n, neg, 100.0 * neg / n));
                sb.append("Top themes: ").append(freq.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(6).map(x -> x.getKey() + " (" + x.getValue() + ")").collect(Collectors.joining(", "))).append("\n\n");
                sb.append(neg > pos ? "Verdict: Mostly NEGATIVE. Review logistics, timing and content." : pos > neg ? "Verdict: Mostly POSITIVE. Keep the format and repeat it." : "Verdict: MIXED. Look closely at the negative comments.");
            }
            return sb.toString();
        }

        // ---- Chat ----
        String snapshot() {
            StringBuilder sb = new StringBuilder("Today: " + LocalDate.now() + "\nEvents:\n");
            for (EventItem e : store.events)
                sb.append(String.format("%s | %s | %s | %s %s | %s | %d/%d registered | %d checked-in | fee %.0f%n", e.id, e.name, e.category, e.date, e.time, e.venue, regCount(e.id), e.capacity, inCount(e.id), e.fee));
            return sb.toString();
        }
        String localAnswer(String q) {
            q = q.toLowerCase(); LocalDate today = LocalDate.now();
            List<EventItem> up = store.events.stream().filter(e -> !e.date.isBefore(today)).sorted(Comparator.comparing((EventItem e) -> e.date).thenComparing(e -> e.time)).collect(Collectors.toList());
            if (q.contains("help")) return "Try: 'upcoming events', 'how many participants', 'attendance rate', 'revenue', 'most popular event', 'free venues tomorrow'.";
            if (q.contains("upcoming") || q.contains("next event")) {
                if (up.isEmpty()) return "There are no upcoming events.";
                return "Upcoming: " + up.stream().limit(5).map(e -> e.name + " on " + e.date + " at " + e.time + " (" + e.venue + ")").collect(Collectors.joining("; ")) + ".";
            }
            if (q.contains("popular") || q.contains("most registered")) {
                return store.events.stream().max(Comparator.comparingInt(e -> regCount(e.id))).map(e -> "The most popular event is " + e.name + " with " + regCount(e.id) + " registrations.").orElse("No events yet.");
            }
            if (q.contains("attendance")) {
                int r = 0, i = 0; for (EventItem e : store.events) if (e.date.isBefore(today)) { r += regCount(e.id); i += inCount(e.id); }
                return r == 0 ? "No past-event data yet." : String.format("Overall attendance for past events is %.0f%% (%d of %d).", 100.0 * i / r, i, r);
            }
            if (q.contains("revenue") || q.contains("income")) return "Total expected revenue is Rs. " + (long) store.events.stream().mapToDouble(e -> regCount(e.id) * e.fee).sum() + ".";
            if (q.contains("venue") || q.contains("free") || q.contains("available")) {
                LocalDate d = today.plusDays(1); List<String> free = new ArrayList<>();
                for (String v : VENUES.keySet()) if (conflict(v, d, LocalTime.of(10, 0), 120, null) == null) free.add(v);
                return "Venues free tomorrow (" + d + ") 10:00-12:00: " + (free.isEmpty() ? "none" : String.join(", ", free)) + ".";
            }
            if (q.contains("participant") || q.contains("registered")) return "There are " + store.participants.size() + " participants across " + store.events.size() + " events.";
            if (q.contains("how many") && q.contains("event")) return "There are " + store.events.size() + " events (" + up.size() + " upcoming).";
            if (q.contains("best time") || q.contains("schedule")) return "Open the 'Smart Scheduler' tab: it finds conflict-free slots ranked by fit.";
            return "I did not understand that. Type 'help', or enter a Claude API key above for open-ended answers.";
        }
        String modelName() {
            Object o = cbModel.getEditor().getItem(); String s = o == null ? "" : o.toString().trim();
            return s.isEmpty() ? MODELS[0] : s;
        }
        void testApi() {
            String key = new String(pfKey.getPassword()).trim();
            if (key.isEmpty()) { err(this, "Enter your Claude API key first."); return; }
            String model = modelName();
            test.setEnabled(false); status.setText("Testing connection to " + model + " ...");
            new SwingWorker<String, Void>() {
                protected String doInBackground() throws Exception {
                    List<String[]> m = new ArrayList<>(); m.add(new String[]{"user", "Reply with the single word: OK"});
                    return callClaude(key, model, "You are a connectivity test.", m);
                }
                protected void done() {
                    test.setEnabled(true); status.setText(" ");
                    try { info(AIPanel.this, "Connection successful!\nModel: " + model + "\nReply: " + trunc(get().trim(), 80)); }
                    catch (Exception ex) { Throwable c = ex.getCause() != null ? ex.getCause() : ex; err(AIPanel.this, "Connection failed:\n" + c.getMessage()); }
                }
            }.execute();
        }
        void sendChat() {
            String q = tfIn.getText().trim(); if (q.isEmpty()) return; tfIn.setText(""); log.append("You: " + q + "\n");
            String key = new String(pfKey.getPassword()).trim();
            if (key.isEmpty()) { log.append("Assistant: " + localAnswer(q) + "\n\n"); log.setCaretPosition(log.getDocument().getLength()); return; }
            String model = modelName();
            send.setEnabled(false); status.setText("Thinking...");
            final List<String[]> msgs = new ArrayList<>(hist); msgs.add(new String[]{"user", q});
            final String system = "You are the assistant inside a Smart Event Management System. Answer briefly and only from this data when relevant.\n" + snapshot();
            new SwingWorker<String, Void>() {
                protected String doInBackground() throws Exception { return callClaude(key, model, system, msgs); }
                protected void done() {
                    send.setEnabled(true); status.setText(" "); String r;
                    try {
                        r = get();
                        hist.add(new String[]{"user", q}); hist.add(new String[]{"assistant", r});
                        while (hist.size() > 12) hist.remove(0);
                    } catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        r = "(AI service error: " + c.getMessage() + ")\nOffline answer: " + localAnswer(q);
                    }
                    log.append("Assistant: " + r + "\n\n"); log.setCaretPosition(log.getDocument().getLength());
                }
            }.execute();
        }
    }

    // ======================= CLAUDE API =======================
    static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break; case '\\': b.append("\\\\"); break; case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break; case '\t': b.append("\\t"); break;
                default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }
    /** Returns every string value found for the given JSON key (tolerant of spaces, ignores matches that are values rather than keys). */
    static List<String> jsonStrings(String j, String key) {
        List<String> out = new ArrayList<>(); String pat = "\"" + key + "\""; int i = 0, len = j.length();
        while ((i = j.indexOf(pat, i)) >= 0) {
            int p = i + pat.length();
            while (p < len && Character.isWhitespace(j.charAt(p))) p++;
            if (p >= len || j.charAt(p) != ':') { i++; continue; }
            p++; while (p < len && Character.isWhitespace(j.charAt(p))) p++;
            if (p >= len || j.charAt(p) != '"') { i = p; continue; }
            p++; StringBuilder sb = new StringBuilder();
            while (p < len) {
                char c = j.charAt(p);
                if (c == '"') break;
                if (c == '\\' && p + 1 < len) {
                    char n = j.charAt(++p);
                    switch (n) {
                        case 'n': sb.append('\n'); break; case 't': sb.append('\t'); break; case 'r': break;
                        case 'b': case 'f': break;
                        case 'u': if (p + 4 < len) { sb.append((char) Integer.parseInt(j.substring(p + 1, p + 5), 16)); p += 4; } break;
                        default: sb.append(n);
                    }
                } else sb.append(c);
                p++;
            }
            out.add(sb.toString()); i = p + 1;
        }
        return out;
    }
    static String callClaude(String key, String model, String system, List<String[]> msgs) throws Exception {
        for (char c : key.toCharArray()) if (c < 33 || c > 126) throw new IOException("The API key contains invalid characters (spaces or hidden characters). Re-paste it.");
        StringBuilder ms = new StringBuilder("[");
        for (int i = 0; i < msgs.size(); i++) {
            if (i > 0) ms.append(',');
            ms.append("{\"role\":").append(js(msgs.get(i)[0])).append(",\"content\":").append(js(msgs.get(i)[1])).append('}');
        }
        ms.append(']');
        String body = "{\"model\":" + js(model) + ",\"max_tokens\":1024,\"system\":" + js(system) + ",\"messages\":" + ms + "}";
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                .header("content-type", "application/json; charset=utf-8").header("x-api-key", key).header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(90)).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> r;
        try { r = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
        catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException ex) { throw new IOException("Cannot reach api.anthropic.com. Check your internet connection, firewall or proxy."); }
        String b = r.body();
        if (r.statusCode() != 200) {
            List<String> m = jsonStrings(b, "message"); String detail = m.isEmpty() ? trunc(b, 200) : m.get(0);
            String hint;
            switch (r.statusCode()) {
                case 401: hint = "Invalid API key."; break;
                case 403: hint = "This key is not allowed to use this model or endpoint."; break;
                case 404: hint = "Model '" + model + "' was not found. Pick another model name."; break;
                case 400: hint = "Request rejected (check the model name and your account credit balance)."; break;
                case 429: hint = "Rate limit reached. Wait a moment and try again."; break;
                default: hint = r.statusCode() >= 500 ? "Anthropic service is temporarily unavailable. Try again shortly." : "Unexpected error.";
            }
            throw new IOException("HTTP " + r.statusCode() + " - " + hint + " [" + detail + "]");
        }
        List<String> texts = jsonStrings(b, "text");
        if (texts.isEmpty()) throw new IOException("Unexpected response from API: " + trunc(b, 200));
        return String.join("", texts);
    }

    // ======================= DEMO DATA =======================
    void loadDemo() {
        store = new Store(); Random r = new Random(); LocalDate t = LocalDate.now();
        mk("Tech Fest 2026", "Technical", t.minusDays(25), 10, 0, 240, "Seminar Hall A", 120, 100, "Annual tech fest with talks and hackathon.");
        mk("Cultural Night", "Cultural", t.minusDays(12), 18, 0, 180, "Open Air Theatre", 300, 0, "Music, dance and drama.");
        mk("AI Workshop", "Workshop", t.minusDays(4), 11, 0, 180, "Computer Lab 1", 60, 250, "Hands-on machine learning workshop.");
        mk("Startup Summit", "Business", t.plusDays(6), 10, 0, 300, "Banquet Hall", 200, 500, "Founders and investors meet.");
        mk("Inter-College Sports Meet", "Sports", t.plusDays(13), 9, 0, 480, "Open Air Theatre", 300, 0, "Athletics and team games.");
        String[] fn = {"Aarav", "Diya", "Rohan", "Isha", "Kabir", "Meera", "Arjun", "Sana", "Vihaan", "Anaya", "Riya", "Kunal", "Neha", "Yash", "Tara"};
        String[] ln = {"Sharma", "Verma", "Rawat", "Negi", "Bisht", "Singh", "Gupta", "Joshi", "Mehta", "Thakur"};
        String[] dp = {"CSE", "ECE", "ME", "Civil", "MBA", "BBA"};
        String[] good = {"Great event, very informative", "Loved the speakers and the energy", "Well organised and engaging", "Amazing experience", "Good content but too crowded", "Late start and noisy venue", "Excellent workshop, very helpful"};
        for (EventItem e : store.events) {
            int n = (int) (e.capacity * (0.35 + r.nextDouble() * 0.5));
            for (int i = 0; i < n; i++) {
                Person p = new Person(); p.id = "P" + (++store.pCounter); String f = fn[r.nextInt(fn.length)], l = ln[r.nextInt(ln.length)];
                p.name = f + " " + l; p.email = (f + "." + l + p.id).toLowerCase() + "@mail.com"; p.phone = "98" + (10000000 + r.nextInt(89999999));
                p.dept = dp[r.nextInt(dp.length)]; p.eventId = e.id; p.role = r.nextInt(12) == 0 ? ROLES[1 + r.nextInt(4)] : "Attendee";
                if (e.date.isBefore(LocalDate.now()) && r.nextInt(100) < 80) {
                    p.checkedIn = true;
                    if (r.nextBoolean()) { p.rating = 3 + r.nextInt(3); p.feedback = good[r.nextInt(good.length)]; }
                }
                store.participants.add(p);
            }
        }
    }
    void mk(String n, String c, LocalDate d, int h, int m, int dur, String v, int cap, double fee, String desc) {
        EventItem e = new EventItem(); e.id = "E" + (++store.evCounter); e.name = n; e.category = c; e.date = d; e.time = LocalTime.of(h, m);
        e.durationMin = dur; e.venue = v; e.capacity = Math.min(cap, VENUES.get(v)); e.fee = fee; e.description = desc; store.events.add(e);
    }

    // ======================= MAIN =======================
    public static void main(String[] args) {
        load();
        try {
            for (UIManager.LookAndFeelInfo i : UIManager.getInstalledLookAndFeels())
                if ("Nimbus".equals(i.getName())) {
                    UIManager.put("nimbusFocus", PRIMARY);
                    UIManager.put("nimbusSelectionBackground", new Color(199, 210, 254));
                    UIManager.setLookAndFeel(i.getClassName()); break;
                }
            for (Object k : Collections.list(UIManager.getDefaults().keys()))
                if (UIManager.get(k) instanceof javax.swing.plaf.FontUIResource) UIManager.put(k, new javax.swing.plaf.FontUIResource(F));
        } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> new SmartEventManager().setVisible(true));
    }
}