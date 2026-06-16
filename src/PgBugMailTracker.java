import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.Desktop;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PgBugMailTracker extends JFrame {
    private final File storeFile = new File("data/records.tsv");
    private final File exportFile = new File("data/pg-bug-records.csv");
    private final RecordStore store = new RecordStore(storeFile);
    private final BugTableModel tableModel = new BugTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextField filterField = new JTextField();
    private final JSpinner yearSpinner = new JSpinner(new SpinnerNumberModel(Calendar.getInstance().get(Calendar.YEAR), 1997, 2100, 1));
    private final JSpinner monthSpinner = new JSpinner(new SpinnerNumberModel(Calendar.getInstance().get(Calendar.MONTH) + 1, 1, 12, 1));
    private final JSpinner monthsSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 36, 1));
    private final JSpinner limitSpinner = new JSpinner(new SpinnerNumberModel(120, 1, 1000, 10));
    private final JLabel statusLabel = new JLabel("就绪");

    private final JTextField bugIdField = readOnlyField();
    private final JTextField subjectField = readOnlyField();
    private final JTextField dateField = readOnlyField();
    private final JTextField fromField = readOnlyField();
    private final JTextField messageIdField = readOnlyField();
    private final JTextField urlField = readOnlyField();
    private final JTextField versionField = new JTextField();
    private final JTextField statusField = new JTextField("未分析");
    private final JTextField severityField = new JTextField();
    private final JTextField tagsField = new JTextField();
    private final JTextArea errorArea = area(4);
    private final JTextArea reproArea = area(8);
    private final JTextArea stepsArea = area(7);
    private final JTextArea notesArea = area(5);
    private final JTextArea rawArea = area(10);

    private BugRecord selected;
    private boolean loadingRecord;

    public static void main(String[] args) {
        if (args.length > 0 && "--fetch-test".equals(args[0])) {
            runFetchTest(args);
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                new PgBugMailTracker().setVisible(true);
            }
        });
    }

    private static void runFetchTest(String[] args) {
        try {
            Calendar cal = Calendar.getInstance();
            int year = args.length > 1 ? Integer.parseInt(args[1]) : cal.get(Calendar.YEAR);
            int month = args.length > 2 ? Integer.parseInt(args[2]) : cal.get(Calendar.MONTH) + 1;
            int limit = args.length > 3 ? Integer.parseInt(args[3]) : 2;
            List<BugRecord> records = new PgArchiveClient().fetchMonth(year, month, limit);
            System.out.println("Fetched: " + records.size());
            for (BugRecord r : records) {
                System.out.println("Subject: " + r.subject);
                System.out.println("Bug ID: " + r.bugId);
                System.out.println("Date: " + r.date);
                System.out.println("Version: " + r.pgVersion);
                System.out.println("Repro:");
                System.out.println(r.reproCode.isEmpty() ? "(empty)" : r.reproCode);
                System.out.println("URL: " + r.url);
                System.out.println("---");
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public PgBugMailTracker() {
        super("PG Bug 邮件记录器");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1180, 760));
        setLocationByPlatform(true);
        setAppIcon();

        try {
            tableModel.setRecords(store.load());
        } catch (IOException e) {
            showError("加载本地数据失败", e);
        }

        setJMenuBar(buildMenu());
        add(buildRoot(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        bindEvents();
    }

    private void setAppIcon() {
        try {
            File icon = new File("assets/pg-bug-mail-64.png");
            if (icon.exists()) setIconImage(Toolkit.getDefaultToolkit().getImage(icon.getAbsolutePath()));
        } catch (Exception ignored) {
            // The default Java icon is acceptable if the custom asset is missing.
        }
    }

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("文件");
        JMenuItem save = new JMenuItem(new AbstractAction("保存") {
            @Override public void actionPerformed(ActionEvent e) { saveAll(); }
        });
        JMenuItem export = new JMenuItem(new AbstractAction("导出 CSV") {
            @Override public void actionPerformed(ActionEvent e) { exportCsv(); }
        });
        file.add(save);
        file.add(export);
        bar.add(file);
        return bar;
    }

    private JComponent buildRoot() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.add(buildToolbar(), BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(85);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.getColumnModel().getColumn(2).setPreferredWidth(430);
        table.getColumnModel().getColumn(3).setPreferredWidth(90);
        table.getColumnModel().getColumn(4).setPreferredWidth(90);
        table.getColumnModel().getColumn(5).setPreferredWidth(130);
        JScrollPane listPane = new JScrollPane(table);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPane, buildDetailPanel());
        split.setResizeWeight(0.43);
        root.add(split, BorderLayout.CENTER);
        return root;
    }

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 4, 0, 4);
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;

        addLabel(bar, c, "起始年");
        bar.add(yearSpinner, c);
        addLabel(bar, c, "月");
        bar.add(monthSpinner, c);
        addLabel(bar, c, "连续月份");
        bar.add(monthsSpinner, c);
        addLabel(bar, c, "每月最多");
        bar.add(limitSpinner, c);
        JButton fetch = new JButton("抓取邮件");
        fetch.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { fetchInBackground(); }
        });
        bar.add(fetch, c);
        JButton open = new JButton("打开原文");
        open.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { openSelectedUrl(); }
        });
        bar.add(open, c);
        JButton save = new JButton("保存");
        save.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { saveAll(); }
        });
        bar.add(save, c);

        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        filterField.setToolTipText("按主题、版本、报错、标签过滤");
        bar.add(filterField, c);
        return bar;
    }

    private void addLabel(JPanel panel, GridBagConstraints c, String text) {
        panel.add(new JLabel(text), c);
    }

    private JComponent buildDetailPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("邮件信息", buildFormPanel());
        tabs.addTab("原文", new JScrollPane(rawArea));
        panel.add(tabs, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildFormPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(2, 2, 2, 2));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        int row = 0;
        row = addRow(form, c, row, "Bug 编号", bugIdField);
        row = addRow(form, c, row, "主题", subjectField);
        row = addRow(form, c, row, "日期", dateField);
        row = addRow(form, c, row, "作者", fromField);
        row = addRow(form, c, row, "Message-ID", messageIdField);
        row = addRow(form, c, row, "URL", urlField);
        row = addRow(form, c, row, "PG 版本", versionField);
        row = addTwoFields(form, c, row, "状态", statusField, "严重级别", severityField);
        row = addRow(form, c, row, "标签", tagsField);
        row = addAreaRow(form, c, row, "报错信息", errorArea);
        row = addAreaRow(form, c, row, "复现案例代码", reproArea);
        row = addAreaRow(form, c, row, "问题排查步骤", stepsArea);
        addAreaRow(form, c, row, "备注", notesArea);
        return new JScrollPane(form);
    }

    private int addRow(JPanel form, GridBagConstraints c, int row, String label, JComponent comp) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.gridwidth = 3;
        c.weightx = 1;
        form.add(comp, c);
        c.gridwidth = 1;
        return row + 1;
    }

    private int addTwoFields(JPanel form, GridBagConstraints c, int row, String a, JComponent ac, String b, JComponent bc) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        form.add(new JLabel(a), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(ac, c);
        c.gridx = 2;
        c.weightx = 0;
        form.add(new JLabel(b), c);
        c.gridx = 3;
        c.weightx = 1;
        form.add(bc, c);
        return row + 1;
    }

    private int addAreaRow(JPanel form, GridBagConstraints c, int row, String label, JTextArea area) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.gridwidth = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.BOTH;
        form.add(new JScrollPane(area), c);
        c.gridwidth = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        return row + 1;
    }

    private void bindEvents() {
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = table.getSelectedRow();
                if (row >= 0) {
                    int modelRow = table.convertRowIndexToModel(row);
                    showRecord(tableModel.getRecord(modelRow));
                }
            }
        });
        DocumentListener listener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateSelected(); }
            @Override public void removeUpdate(DocumentEvent e) { updateSelected(); }
            @Override public void changedUpdate(DocumentEvent e) { updateSelected(); }
        };
        for (JTextComponentLike field : editableComponents()) {
            field.addDocumentListener(listener);
        }
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
    }

    private List<JTextComponentLike> editableComponents() {
        List<JTextComponentLike> list = new ArrayList<JTextComponentLike>();
        list.add(new TextFieldLike(versionField));
        list.add(new TextFieldLike(statusField));
        list.add(new TextFieldLike(severityField));
        list.add(new TextFieldLike(tagsField));
        list.add(new TextAreaLike(errorArea));
        list.add(new TextAreaLike(reproArea));
        list.add(new TextAreaLike(stepsArea));
        list.add(new TextAreaLike(notesArea));
        return list;
    }

    private void fetchInBackground() {
        final int year = (Integer) yearSpinner.getValue();
        final int month = (Integer) monthSpinner.getValue();
        final int months = (Integer) monthsSpinner.getValue();
        final int limit = (Integer) limitSpinner.getValue();
        setBusy("正在抓取...");
        SwingWorker<Integer, Object> worker = new SwingWorker<Integer, Object>() {
            private int changed;

            @Override protected Integer doInBackground() throws Exception {
                PgArchiveClient client = new PgArchiveClient();
                int fetched = 0;
                Calendar cal = new GregorianCalendar(year, month - 1, 1);
                for (int i = 0; i < months; i++) {
                    int y = cal.get(Calendar.YEAR);
                    int m = cal.get(Calendar.MONTH) + 1;
                    final String ym = y + "-" + two(m);
                    publish("读取索引 " + ym + " (" + (i + 1) + "/" + months + ")");
                    List<BugRecord> monthRecords = client.fetchMonth(y, m, limit, new FetchProgress() {
                        @Override public void onProgress(String message) {
                            publish(message);
                        }

                        @Override public void onRecord(BugRecord record) {
                            publish(record);
                        }
                    });
                    fetched += monthRecords.size();
                    publish("完成 " + ym + "，本月 " + monthRecords.size() + " 封，累计 " + fetched + " 封");
                    cal.add(Calendar.MONTH, -1);
                }
                return fetched;
            }

            @Override protected void process(List<Object> chunks) {
                for (Object chunk : chunks) {
                    if (chunk instanceof String) {
                        statusLabel.setText((String) chunk);
                    } else if (chunk instanceof BugRecord) {
                        changed += tableModel.merge(Collections.singletonList((BugRecord) chunk));
                        if (table.getSelectedRow() < 0 && tableModel.getRowCount() > 0) {
                            table.setRowSelectionInterval(0, 0);
                        }
                        statusLabel.setText("已显示 " + tableModel.getRowCount() + " 封邮件，继续抓取中...");
                    }
                }
            }

            @Override protected void done() {
                try {
                    int fetched = get();
                    store.save(tableModel.allRecords());
                    statusLabel.setText("抓取完成，获取 " + fetched + " 封邮件，新增/更新 " + changed + " 条记录");
                } catch (Exception e) {
                    showError("抓取失败", e);
                    statusLabel.setText("抓取失败");
                }
            }
        };
        worker.execute();
    }

    private void showRecord(BugRecord r) {
        selected = r;
        loadingRecord = true;
        bugIdField.setText(r.bugId);
        subjectField.setText(r.subject);
        dateField.setText(r.date);
        fromField.setText(r.from);
        messageIdField.setText(r.messageId);
        urlField.setText(r.url);
        versionField.setText(r.pgVersion);
        statusField.setText(r.status);
        severityField.setText(r.severity);
        tagsField.setText(r.tags);
        errorArea.setText(r.errorInfo);
        reproArea.setText(r.reproCode);
        stepsArea.setText(r.diagnosticSteps);
        notesArea.setText(r.notes);
        rawArea.setText(r.rawText);
        rawArea.setCaretPosition(0);
        loadingRecord = false;
    }

    private void updateSelected() {
        if (loadingRecord || selected == null) return;
        selected.pgVersion = versionField.getText();
        selected.status = statusField.getText();
        selected.severity = severityField.getText();
        selected.tags = tagsField.getText();
        selected.errorInfo = errorArea.getText();
        selected.reproCode = reproArea.getText();
        selected.diagnosticSteps = stepsArea.getText();
        selected.notes = notesArea.getText();
        selected.updatedAt = now();
        tableModel.fireTableDataChanged();
    }

    private void applyFilter() {
        tableModel.setFilter(filterField.getText());
    }

    private void saveAll() {
        try {
            store.save(tableModel.allRecords());
            statusLabel.setText("已保存到 " + storeFile.getPath());
        } catch (IOException e) {
            showError("保存失败", e);
        }
    }

    private void exportCsv() {
        try {
            exportFile.getParentFile().mkdirs();
            CsvExporter.export(tableModel.allRecords(), exportFile);
            statusLabel.setText("已导出 " + exportFile.getPath());
        } catch (IOException e) {
            showError("导出失败", e);
        }
    }

    private void openSelectedUrl() {
        if (selected == null || selected.url.trim().isEmpty()) return;
        try {
            Desktop.getDesktop().browse(new URI(selected.url));
        } catch (Exception e) {
            showError("打开 URL 失败", e);
        }
    }

    private void setBusy(String text) {
        statusLabel.setText(text);
    }

    private void showError(String title, Exception e) {
        JOptionPane.showMessageDialog(this, title + ":\n" + e.getMessage(), title, JOptionPane.ERROR_MESSAGE);
    }

    private static JTextField readOnlyField() {
        JTextField f = new JTextField();
        f.setEditable(false);
        return f;
    }

    private static JTextArea area(int rows) {
        JTextArea a = new JTextArea(rows, 40);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        return a;
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    interface JTextComponentLike {
        void addDocumentListener(DocumentListener listener);
    }

    static class TextFieldLike implements JTextComponentLike {
        private final JTextField field;
        TextFieldLike(JTextField field) { this.field = field; }
        @Override public void addDocumentListener(DocumentListener listener) { field.getDocument().addDocumentListener(listener); }
    }

    static class TextAreaLike implements JTextComponentLike {
        private final JTextArea area;
        TextAreaLike(JTextArea area) { this.area = area; }
        @Override public void addDocumentListener(DocumentListener listener) { area.getDocument().addDocumentListener(listener); }
    }
}

class BugRecord {
    String bugId = "";
    String messageId = "";
    String subject = "";
    String from = "";
    String date = "";
    String url = "";
    String pgVersion = "";
    String errorInfo = "";
    String reproCode = "";
    String diagnosticSteps = "";
    String status = "未分析";
    String severity = "";
    String tags = "";
    String notes = "";
    String rawText = "";
    String createdAt = "";
    String updatedAt = "";
}

class BugTableModel extends AbstractTableModel {
    private final String[] columns = {"Bug 编号", "日期", "主题", "PG 版本", "状态", "严重级别"};
    private final List<BugRecord> all = new ArrayList<BugRecord>();
    private final List<BugRecord> shown = new ArrayList<BugRecord>();
    private String filter = "";

    public void setRecords(List<BugRecord> records) {
        all.clear();
        merge(records);
    }

    public int merge(List<BugRecord> incoming) {
        Map<String, BugRecord> byId = new LinkedHashMap<String, BugRecord>();
        for (BugRecord r : all) byId.put(key(r), r);
        int changed = 0;
        for (BugRecord n : incoming) {
            String k = key(n);
            BugRecord old = byId.get(k);
            if (old == null) {
                n.bugId = first(n.bugId, Extractor.extractBugId(n.subject + "\n" + n.rawText));
                all.add(n);
                byId.put(k, n);
                changed++;
            } else {
                old.bugId = first(old.bugId, n.bugId);
                old.subject = preferBugSubject(old.subject, n.subject);
                old.from = first(n.from, old.from);
                old.date = first(n.date, old.date);
                old.url = first(n.url, old.url);
                old.messageId = appendUnique(old.messageId, n.messageId);
                old.rawText = appendMailText(old.rawText, n);
                if (old.pgVersion.trim().isEmpty()) old.pgVersion = n.pgVersion;
                old.errorInfo = appendUniqueBlock(old.errorInfo, n.errorInfo);
                if (Extractor.shouldRefreshRepro(old.reproCode) && !n.reproCode.trim().isEmpty()) {
                    old.reproCode = n.reproCode;
                } else {
                    old.reproCode = appendUniqueBlock(old.reproCode, n.reproCode);
                }
                if (old.diagnosticSteps.trim().isEmpty()) old.diagnosticSteps = n.diagnosticSteps;
                old.updatedAt = PgBugMailTrackerHelper.now();
                changed++;
            }
        }
        applyFilter();
        return changed;
    }

    private static String key(BugRecord r) {
        String bugId = r.bugId == null ? "" : r.bugId.trim();
        if (!bugId.isEmpty()) return "bug:" + bugId;
        return r.messageId == null || r.messageId.trim().isEmpty() ? "url:" + r.url : "msg:" + r.messageId;
    }

    private static String first(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a : b;
    }

    private static String preferBugSubject(String oldSubject, String newSubject) {
        if (oldSubject == null || oldSubject.trim().isEmpty()) return first(newSubject, "");
        if (newSubject == null || newSubject.trim().isEmpty()) return oldSubject;
        boolean oldReply = oldSubject.toLowerCase(Locale.ROOT).startsWith("re:");
        boolean newReply = newSubject.toLowerCase(Locale.ROOT).startsWith("re:");
        return oldReply && !newReply ? newSubject : oldSubject;
    }

    private static String appendUnique(String oldValue, String newValue) {
        if (newValue == null || newValue.trim().isEmpty()) return oldValue == null ? "" : oldValue;
        if (oldValue == null || oldValue.trim().isEmpty()) return newValue;
        return oldValue.contains(newValue) ? oldValue : oldValue + "\n" + newValue;
    }

    private static String appendUniqueBlock(String oldValue, String newValue) {
        if (newValue == null || newValue.trim().isEmpty()) return oldValue == null ? "" : oldValue;
        if (oldValue == null || oldValue.trim().isEmpty()) return newValue;
        return oldValue.contains(newValue) ? oldValue : oldValue + "\n\n" + newValue;
    }

    private static String appendMailText(String oldText, BugRecord incoming) {
        String body = incoming.rawText == null ? "" : incoming.rawText.trim();
        if (body.isEmpty()) return oldText == null ? "" : oldText;
        if (oldText == null || oldText.trim().isEmpty()) return body;
        if (oldText.contains(body)) return oldText;
        String title = incoming.date + " " + incoming.from + " " + incoming.messageId;
        return oldText + "\n\n----- 关联邮件: " + title.trim() + " -----\n" + body;
    }

    public void setFilter(String filter) {
        this.filter = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        applyFilter();
    }

    private void applyFilter() {
        shown.clear();
        for (BugRecord r : all) {
            String hay = (r.bugId + "\n" + r.subject + "\n" + r.pgVersion + "\n" + r.errorInfo + "\n" + r.tags + "\n" + r.status).toLowerCase(Locale.ROOT);
            if (filter.isEmpty() || hay.contains(filter)) shown.add(r);
        }
        fireTableDataChanged();
    }

    public List<BugRecord> allRecords() {
        return new ArrayList<BugRecord>(all);
    }

    public BugRecord getRecord(int row) { return shown.get(row); }
    @Override public int getRowCount() { return shown.size(); }
    @Override public int getColumnCount() { return columns.length; }
    @Override public String getColumnName(int column) { return columns[column]; }
    @Override public Object getValueAt(int rowIndex, int columnIndex) {
        BugRecord r = shown.get(rowIndex);
        switch (columnIndex) {
            case 0: return r.bugId;
            case 1: return r.date;
            case 2: return r.subject;
            case 3: return r.pgVersion;
            case 4: return r.status;
            case 5: return r.severity;
            default: return "";
        }
    }
}

class PgArchiveClient {
    private static final String BASE = "https://www.postgresql.org";
    private static final Pattern LINK = Pattern.compile("<a\\s+[^>]*href=\"([^\"]*/message-id/[^\"]+)\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public List<BugRecord> fetchMonth(int year, int month, int limit) throws IOException {
        return fetchMonth(year, month, limit, null);
    }

    public List<BugRecord> fetchMonth(int year, int month, int limit, FetchProgress progress) throws IOException {
        String url = BASE + "/list/pgsql-bugs/" + year + "-" + two(month) + "/";
        if (progress != null) progress.onProgress("下载索引 " + year + "-" + two(month));
        String html = get(url);
        LinkedHashMap<String, String> links = new LinkedHashMap<String, String>();
        Matcher m = LINK.matcher(html);
        while (m.find() && links.size() < limit) {
            String href = m.group(1);
            String title = clean(decodeEntities(stripTags(m.group(2))));
            if (!href.startsWith("http")) href = BASE + href;
            if (title.toLowerCase(Locale.ROOT).contains("bug") || title.toLowerCase(Locale.ROOT).contains("crash")
                    || title.toLowerCase(Locale.ROOT).contains("segmentation") || title.toLowerCase(Locale.ROOT).contains("assert")
                    || title.toLowerCase(Locale.ROOT).contains("error")) {
                links.put(href, title);
            }
        }
        List<BugRecord> records = new ArrayList<BugRecord>();
        int index = 0;
        int total = links.size();
        for (Map.Entry<String, String> entry : links.entrySet()) {
            try {
                index++;
                if (progress != null) {
                    progress.onProgress("抓取 " + year + "-" + two(month) + " 第 " + index + "/" + total + " 封");
                }
                BugRecord record = fetchMessage(entry.getKey(), entry.getValue());
                records.add(record);
                if (progress != null) progress.onRecord(record);
                Thread.sleep(120L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("抓取被中断", e);
            }
        }
        return records;
    }

    private BugRecord fetchMessage(String url, String fallbackSubject) throws IOException {
        String html = get(url);
        String text = htmlToText(html);
        BugRecord r = new BugRecord();
        r.url = url;
        r.subject = first(match(text, "(?m)^#\\s*(.+)$"), fallbackSubject);
        r.bugId = Extractor.extractBugId(r.subject + "\n" + text);
        r.from = match(text, "(?m)^From:\\s*(.+)$");
        r.date = match(text, "(?m)^Date:\\s*(.+)$");
        r.messageId = clean(match(text, "(?m)^Message-ID:\\s*(.+)$"));
        if (r.messageId.contains(" ")) r.messageId = r.messageId.substring(0, r.messageId.indexOf(' ')).trim();
        r.rawText = text.trim();
        r.pgVersion = Extractor.extractVersion(text);
        r.errorInfo = Extractor.extractError(text);
        r.reproCode = Extractor.extractRepro(text);
        r.diagnosticSteps = Extractor.initialSteps(r);
        r.status = "待复现";
        r.createdAt = PgBugMailTrackerHelper.now();
        r.updatedAt = r.createdAt;
        return r;
    }

    private String get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "PG-Bug-Mail-Tracker/1.0");
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) throw new IOException("HTTP " + code + ": " + url);
        String body = readAll(in);
        if (code >= 400) throw new IOException("HTTP " + code + ": " + url + "\n" + body);
        return body;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String htmlToText(String html) {
        String s = html.replaceAll("(?is)<script.*?</script>", " ");
        s = s.replaceAll("(?is)<style.*?</style>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|li|h1|h2|h3|pre|tr)>", "\n");
        s = stripTags(s);
        s = decodeEntities(s);
        s = s.replace('\u00a0', ' ');
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll("(?m)^\\s+", "");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    private static String stripTags(String s) {
        return s.replaceAll("(?is)<[^>]+>", " ");
    }

    private static String decodeEntities(String s) {
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        return decodeNumericEntities(s);
    }

    private static String decodeNumericEntities(String s) {
        Matcher m = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);").matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String token = m.group(1);
            String replacement = "";
            try {
                int codePoint = token.startsWith("x") || token.startsWith("X")
                        ? Integer.parseInt(token.substring(1), 16)
                        : Integer.parseInt(token, 10);
                replacement = new String(Character.toChars(codePoint));
            } catch (Exception ignored) {
                replacement = m.group(0);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String match(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? clean(m.group(1)) : "";
    }

    private static String first(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a : b;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}

interface FetchProgress {
    void onProgress(String message);
    void onRecord(BugRecord record);
}

class Extractor {
    private static final Pattern BUG_ID = Pattern.compile("(?i)\\bBUG\\s*#\\s*([0-9]+)\\b");
    private static final Pattern VERSION_FIELD = Pattern.compile("(?im)^PostgreSQL\\s+version:\\s*(.+)$");
    private static final Pattern VERSION_INLINE = Pattern.compile("(?i)\\b(?:PG|PostgreSQL)\\s*([0-9]{1,2}(?:\\.[0-9]+)*(?:\\s*beta\\s*[0-9]+|beta[0-9]+)?)\\b");
    private static final Pattern ERROR_LINE = Pattern.compile("(?im)^.*\\b(ERROR|FATAL|PANIC|SIGSEGV|segmentation fault|assert|crash|server closed the connection|OOM|out of memory|NULL dereference|stack buffer overflow)\\b.*$");
    private static final Pattern REPRO_LABEL = Pattern.compile("(?i)^\\s*(test\\s*case|steps?\\s+to\\s+reproduce|repro(?:duction)?|reproduce|sql|query|example)\\s*:?\\s*$");
    private static final Pattern STOP_LABEL = Pattern.compile("(?i)^\\s*(postgresql\\s+version|operating\\s+system|description|details|message-id|from|date|subject|regards|thanks|best)\\s*:");
    private static final Pattern SQL_START = Pattern.compile("(?i)^\\s*(?:[-*]\\s*)?(?:postgres(?:=#|=>)\\s*)?(CREATE|SELECT|INSERT|UPDATE|DELETE|ALTER|DROP|WITH|BEGIN|COMMIT|ROLLBACK|EXPLAIN|LOAD|SET|RESET|COPY|VACUUM|ANALYZE|TRUNCATE|PREPARE|EXECUTE|DO|CALL)\\b.*");

    static String extractBugId(String text) {
        if (text == null) return "";
        Matcher m = BUG_ID.matcher(text);
        return m.find() ? "#" + m.group(1) : "";
    }

    static String extractVersion(String text) {
        Matcher field = VERSION_FIELD.matcher(text);
        if (field.find()) return field.group(1).trim();
        Matcher inline = VERSION_INLINE.matcher(text);
        if (inline.find()) return inline.group(0).trim();
        return "";
    }

    static String extractError(String text) {
        StringBuilder sb = new StringBuilder();
        Matcher m = ERROR_LINE.matcher(text);
        int count = 0;
        while (m.find() && count < 8) {
            String line = m.group().trim();
            if (line.length() > 260) line = line.substring(0, 260) + "...";
            if (sb.indexOf(line) < 0) sb.append(line).append('\n');
            count++;
        }
        return sb.toString().trim();
    }

    static String extractRepro(String text) {
        String block = firstCodeFence(text);
        if (looksLikeRepro(block)) return normalizeCode(block);

        block = labelledReproSection(text);
        if (looksLikeRepro(block)) {
            String codeOnly = sqlBlock(block);
            return normalizeCode(codeOnly.isEmpty() ? block : codeOnly);
        }

        return normalizeCode(sqlBlock(text));
    }

    static boolean shouldRefreshRepro(String repro) {
        if (repro == null || repro.trim().isEmpty()) return false;
        String s = repro.toLowerCase(Locale.ROOT);
        return s.contains("browse pgsql-bugs by date")
                || s.contains("privacy policy")
                || s.contains("copyright ©")
                || s.contains("copyright &copy;")
                || s.contains("previous message")
                || s.contains("next message")
                || s.contains("in response to\n")
                || s.contains("\nresponses\n");
    }

    private static String labelledReproSection(String text) {
        String[] lines = text.split("\\n");
        StringBuilder best = new StringBuilder();
        boolean capture = false;
        int blankRun = 0;
        for (String line : lines) {
            String t = stripQuote(line).trim();
            if (!capture && REPRO_LABEL.matcher(t).matches()) {
                capture = true;
                blankRun = 0;
                continue;
            }
            if (!capture) continue;
            if (isStopLine(t)) break;
            if (t.isEmpty()) {
                blankRun++;
                if (blankRun >= 3 && best.length() > 0) break;
            } else {
                blankRun = 0;
            }
            best.append(stripQuote(line)).append('\n');
            if (best.length() > 5000) break;
        }
        return trimNoise(best.toString());
    }

    private static String sqlBlock(String text) {
        String[] lines = text.split("\\n");
        boolean capture = false;
        int idleLines = 0;
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String cleaned = stripQuote(line);
            String t = cleaned.trim();
            if (!capture && SQL_START.matcher(t).matches()) {
                capture = true;
            }
            if (capture) {
                if (isStopLine(t)) break;
                if (t.isEmpty()) {
                    idleLines++;
                    if (idleLines >= 2 && endsLikeSqlStatement(sb)) break;
                    sb.append('\n');
                    continue;
                }
                if (isNaturalLanguage(t) && sb.length() > 0) break;
                if (!isLikelyCodeLine(t) && endsLikeSqlStatement(sb)) break;
                idleLines = 0;
                sb.append(cleaned).append('\n');
                if (sb.length() > 4000) break;
            }
        }
        return trimNoise(sb.toString());
    }

    private static boolean isLikelyCodeLine(String t) {
        if (isNaturalLanguage(t)) return false;
        return SQL_START.matcher(t).matches()
                || t.startsWith("--")
                || t.startsWith("\\")
                || t.startsWith("(")
                || t.startsWith(")")
                || t.startsWith(",")
                || t.endsWith(";")
                || t.matches("(?i)^(FROM|WHERE|JOIN|LEFT|RIGHT|INNER|OUTER|GROUP|ORDER|HAVING|LIMIT|OFFSET|VALUES|RETURNING|ON|AND|OR|AS)\\b.*")
                || t.matches("^[A-Za-z_][A-Za-z0-9_$.]*\\s+(integer|int|bigint|text|varchar|numeric|boolean|bool|date|timestamp|jsonb?|uuid|serial|primary|references|not|null|default)\\b.*")
                || t.matches(".*(=|<>|!=|<=|>=|\\(|\\)|,|::|\\$[0-9]+).*")
                || t.matches("^[0-9'\"(].*");
    }

    private static boolean isNaturalLanguage(String t) {
        String s = t.toLowerCase(Locale.ROOT);
        if (s.length() < 12) return false;
        if (SQL_START.matcher(t).matches()) return false;
        return s.matches(".*\\b(is|are|was|were|the|this|that|there|where\\s+come|works|correct|special|related|likely|using|queries|version|timezone|result|column)\\b.*")
                && !s.matches(".*(;|:=|=>|=#|\\(|\\)|::).*");
    }

    private static boolean isStopLine(String t) {
        return STOP_LABEL.matcher(t).find()
                || t.equals("Responses")
                || t.equals("Browse pgsql-bugs by date")
                || t.equals("In response to")
                || t.equals("Previous Message")
                || t.equals("Next Message")
                || t.startsWith("Privacy Policy")
                || t.startsWith("Copyright ")
                || t.equals("--")
                || t.startsWith("-- ")
                || t.matches("(?i)^on .+ wrote:$")
                || t.matches("(?i)^>+\\s*on .+ wrote:$")
                || t.matches("^-{3,}\\s*(original message|forwarded message).*");
    }

    private static boolean endsLikeSqlStatement(StringBuilder sb) {
        String s = sb.toString().trim();
        return s.endsWith(";") || s.endsWith("\\g") || s.endsWith("\\gx");
    }

    private static String stripQuote(String line) {
        return line.replaceFirst("^\\s*>+\\s?", "");
    }

    private static boolean looksLikeRepro(String block) {
        if (block == null || block.trim().isEmpty()) return false;
        String s = block.toLowerCase(Locale.ROOT);
        return SQL_START.matcher(block.trim()).find()
                || s.contains("create table")
                || s.contains("select ")
                || s.contains("insert into")
                || s.contains("psql")
                || s.contains("pgbench")
                || s.contains("initdb");
    }

    private static String normalizeCode(String code) {
        return trimNoise(code).replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String trimNoise(String code) {
        if (code == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = code.split("\\n");
        for (String line : lines) {
            String t = stripQuote(line).trim();
            if (sb.length() > 0 && isStopLine(t)) break;
            sb.append(stripQuote(line)).append('\n');
        }
        return sb.toString().trim();
    }

    static String initialSteps(BugRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append("1. 确认环境：PostgreSQL 版本、操作系统、扩展、编译参数、是否 assert build。\n");
        sb.append("2. 使用邮件中的复现 SQL/命令在干净实例中执行，记录完整服务端日志与客户端输出。\n");
        sb.append("3. 若涉及崩溃，开启 core dump 并采集 backtrace；若涉及 planner/executor，补充 EXPLAIN (ANALYZE, VERBOSE, BUFFERS)。\n");
        sb.append("4. 在相邻 PG 小版本/主版本上交叉验证，判断是否为回归或已修复问题。\n");
        sb.append("5. 关联邮件线程后续回复，记录确认结论、补丁链接或 workaround。");
        return sb.toString();
    }

    private static String firstCodeFence(String text) {
        Matcher m = Pattern.compile("(?s)```(?:sql|SQL|sh|bash|text)?\\s*(.*?)```").matcher(text);
        if (m.find()) return m.group(1).trim();
        return "";
    }
}

class RecordStore {
    private static final String[] FIELDS = {
            "bugId", "messageId", "subject", "from", "date", "url", "pgVersion", "errorInfo", "reproCode",
            "diagnosticSteps", "status", "severity", "tags", "notes", "rawText", "createdAt", "updatedAt"
    };
    private final File file;
    RecordStore(File file) { this.file = file; }

    List<BugRecord> load() throws IOException {
        List<BugRecord> records = new ArrayList<BugRecord>();
        if (!file.exists()) return records;
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            String header = reader.readLine();
            if (header == null) return records;
            String[] fields = header.split("\\t", -1);
            if (fields.length == 0 || fields[0].trim().isEmpty()) fields = FIELDS;
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\t", -1);
                BugRecord r = new BugRecord();
                for (int i = 0; i < Math.min(parts.length, fields.length); i++) set(r, fields[i], decode(parts[i]));
                if (r.bugId.trim().isEmpty()) r.bugId = Extractor.extractBugId(r.subject + "\n" + r.rawText);
                if (Extractor.shouldRefreshRepro(r.reproCode)) r.reproCode = Extractor.extractRepro(r.rawText);
                records.add(r);
            }
        } finally {
            reader.close();
        }
        return records;
    }

    void save(List<BugRecord> records) throws IOException {
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        try {
            writer.write(join(FIELDS, "\t"));
            writer.newLine();
            for (BugRecord r : records) {
                List<String> values = new ArrayList<String>();
                for (String f : FIELDS) values.add(encode(get(r, f)));
                writer.write(join(values.toArray(new String[0]), "\t"));
                writer.newLine();
            }
        } finally {
            writer.close();
        }
    }

    private static String encode(String s) {
        if (s == null) s = "";
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String s) {
        if (s == null || s.isEmpty()) return "";
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    private static String get(BugRecord r, String field) {
        if ("bugId".equals(field)) return r.bugId;
        if ("messageId".equals(field)) return r.messageId;
        if ("subject".equals(field)) return r.subject;
        if ("from".equals(field)) return r.from;
        if ("date".equals(field)) return r.date;
        if ("url".equals(field)) return r.url;
        if ("pgVersion".equals(field)) return r.pgVersion;
        if ("errorInfo".equals(field)) return r.errorInfo;
        if ("reproCode".equals(field)) return r.reproCode;
        if ("diagnosticSteps".equals(field)) return r.diagnosticSteps;
        if ("status".equals(field)) return r.status;
        if ("severity".equals(field)) return r.severity;
        if ("tags".equals(field)) return r.tags;
        if ("notes".equals(field)) return r.notes;
        if ("rawText".equals(field)) return r.rawText;
        if ("createdAt".equals(field)) return r.createdAt;
        if ("updatedAt".equals(field)) return r.updatedAt;
        return "";
    }

    private static void set(BugRecord r, String field, String value) {
        if ("bugId".equals(field)) r.bugId = value;
        else if ("messageId".equals(field)) r.messageId = value;
        else if ("subject".equals(field)) r.subject = value;
        else if ("from".equals(field)) r.from = value;
        else if ("date".equals(field)) r.date = value;
        else if ("url".equals(field)) r.url = value;
        else if ("pgVersion".equals(field)) r.pgVersion = value;
        else if ("errorInfo".equals(field)) r.errorInfo = value;
        else if ("reproCode".equals(field)) r.reproCode = value;
        else if ("diagnosticSteps".equals(field)) r.diagnosticSteps = value;
        else if ("status".equals(field)) r.status = value;
        else if ("severity".equals(field)) r.severity = value;
        else if ("tags".equals(field)) r.tags = value;
        else if ("notes".equals(field)) r.notes = value;
        else if ("rawText".equals(field)) r.rawText = value;
        else if ("createdAt".equals(field)) r.createdAt = value;
        else if ("updatedAt".equals(field)) r.updatedAt = value;
    }

    private static String join(String[] values, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(sep);
            sb.append(values[i]);
        }
        return sb.toString();
    }
}

class CsvExporter {
    static void export(List<BugRecord> records, File file) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        try {
            writer.write("bugId,date,subject,pgVersion,status,severity,tags,errorInfo,reproCode,diagnosticSteps,url,messageId,notes");
            writer.newLine();
            for (BugRecord r : records) {
                writer.write(csv(r.bugId) + "," + csv(r.date) + "," + csv(r.subject) + "," + csv(r.pgVersion) + "," + csv(r.status) + ","
                        + csv(r.severity) + "," + csv(r.tags) + "," + csv(r.errorInfo) + "," + csv(r.reproCode) + ","
                        + csv(r.diagnosticSteps) + "," + csv(r.url) + "," + csv(r.messageId) + "," + csv(r.notes));
                writer.newLine();
            }
        } finally {
            writer.close();
        }
    }

    private static String csv(String value) {
        if (value == null) value = "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}

class PgBugMailTrackerHelper {
    static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}
