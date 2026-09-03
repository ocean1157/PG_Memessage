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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

public class PgBugMailTracker extends JFrame {
    private final File storeFile = new File("data/records.tsv");
    private final File exportFile = new File("data/pg-bug-records.csv");
    private final RecordStore store = new RecordStore(storeFile);
    private final BugTableModel tableModel = new BugTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTabbedPane detailTabs = new JTabbedPane();
    private final JTextField filterField = new JTextField();
    private final JSpinner yearSpinner = new JSpinner(new SpinnerNumberModel(Calendar.getInstance().get(Calendar.YEAR), 1997, 2100, 1));
    private final JSpinner monthSpinner = new JSpinner(new SpinnerNumberModel(Calendar.getInstance().get(Calendar.MONTH) + 1, 1, 12, 1));
    private final JSpinner monthsSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 36, 1));
    private final JSpinner limitSpinner = new JSpinner(new SpinnerNumberModel(120, 1, 1000, 10));
    private final JComboBox<TranslationLanguage> translationTarget = new JComboBox<TranslationLanguage>(ProfessionalTranslator.languages());
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
    private final JEditorPane conversationPane = conversationPane();
    private final JTextArea rawArea = readOnlyArea(10);

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
            BugTableModel merged = new BugTableModel();
            merged.merge(records);
            System.out.println("Fetched: " + records.size());
            System.out.println("Merged: " + merged.allRecords().size());
            for (BugRecord r : records) {
                System.out.println("Subject: " + r.subject);
                System.out.println("Bug ID: " + r.bugId);
                System.out.println("Thread Key: " + r.threadKey);
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
        table.getColumnModel().getColumn(0).setPreferredWidth(48);
        table.getColumnModel().getColumn(0).setMaxWidth(58);
        table.getColumnModel().getColumn(1).setPreferredWidth(85);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(430);
        table.getColumnModel().getColumn(4).setPreferredWidth(90);
        table.getColumnModel().getColumn(5).setPreferredWidth(90);
        table.getColumnModel().getColumn(6).setPreferredWidth(130);
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
        addLabel(bar, c, "翻译为");
        translationTarget.setToolTipText("源语言自动识别，只翻译自然语言描述，代码和专业标识尽量保留");
        bar.add(translationTarget, c);
        JButton translate = new JButton("翻译原文");
        translate.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { translateSelected(); }
        });
        bar.add(translate, c);
        JButton original = new JButton("显示原文");
        original.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { showOriginalConversation(); }
        });
        bar.add(original, c);
        JButton delete = new JButton("删除记录");
        delete.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { deleteSelectedRecord(); }
        });
        bar.add(delete, c);
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
        detailTabs.addTab("邮件信息", buildFormPanel());
        detailTabs.addTab("对话原文", new JScrollPane(conversationPane));
        detailTabs.addTab("纯文本原文", new JScrollPane(rawArea));
        panel.add(detailTabs, BorderLayout.CENTER);
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
        conversationPane.setText(ConversationRenderer.render(r));
        conversationPane.setCaretPosition(0);
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

    private void deleteSelectedRecord() {
        List<BugRecord> checked = tableModel.checkedRecords();
        final List<BugRecord> recordsToDelete = checked.isEmpty()
                ? (selected == null ? Collections.<BugRecord>emptyList() : Collections.singletonList(selected))
                : checked;
        if (recordsToDelete.isEmpty()) {
            statusLabel.setText("请先勾选或选择要删除的邮件记录");
            return;
        }
        String label = deleteSummary(recordsToDelete);
        int result = JOptionPane.showConfirmDialog(this,
                "确定删除 " + recordsToDelete.size() + " 条本地记录吗？\n\n" + label
                        + "\n\n只会删除本地 data/records.tsv 中的记录，不会影响 PostgreSQL 官方邮件归档。",
                "删除邮件记录",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) return;

        int viewRow = table.getSelectedRow();
        boolean selectedDeleted = selected != null && recordsToDelete.contains(selected);
        int removed = tableModel.removeRecords(recordsToDelete);
        if (removed > 0) {
            try {
                store.save(tableModel.allRecords());
                statusLabel.setText("已删除 " + removed + " 条本地记录");
            } catch (IOException e) {
                showError("删除后保存失败", e);
            }
        }

        if (selectedDeleted && table.getRowCount() > 0) {
            int next = Math.min(Math.max(viewRow, 0), table.getRowCount() - 1);
            table.setRowSelectionInterval(next, next);
        } else if (table.getRowCount() == 0) {
            clearDetail();
        }
    }

    private String deleteSummary(List<BugRecord> records) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(records.size(), 8);
        for (int i = 0; i < limit; i++) {
            BugRecord r = records.get(i);
            String name = r.bugId.trim().isEmpty() ? r.subject : r.bugId + " " + r.subject;
            if (name.trim().isEmpty()) name = "未命名邮件";
            sb.append(i + 1).append(". ").append(name).append('\n');
        }
        if (records.size() > limit) sb.append("... 另有 ").append(records.size() - limit).append(" 条\n");
        return sb.toString().trim();
    }

    private void showOriginalConversation() {
        if (selected == null) {
            statusLabel.setText("请先选择邮件记录");
            return;
        }
        conversationPane.setText(ConversationRenderer.render(selected));
        conversationPane.setCaretPosition(0);
        detailTabs.setSelectedIndex(1);
        statusLabel.setText("已显示对话原文");
    }

    private void translateSelected() {
        if (selected == null) {
            statusLabel.setText("请先选择要翻译的邮件记录");
            return;
        }
        final BugRecord record = selected;
        final TranslationLanguage target = (TranslationLanguage) translationTarget.getSelectedItem();
        if (!ConversationRenderer.hasTranslatableBody(record)) {
            conversationPane.setText(ConversationRenderer.renderTranslationNotice(record, target, "当前记录没有可翻译的邮件正文。"));
            conversationPane.setCaretPosition(0);
            detailTabs.setSelectedIndex(1);
            return;
        }

        conversationPane.setText(ConversationRenderer.renderTranslationNotice(record, target,
                "正在翻译为 " + target.name + "，请稍候。代码、SQL、路径、日志、错误码和 PostgreSQL 专业术语会尽量保留原文。"));
        conversationPane.setCaretPosition(0);
        detailTabs.setSelectedIndex(1);
        statusLabel.setText("正在翻译原文为 " + target.name + "...");

        SwingWorker<String, Void> worker = new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                return ConversationRenderer.renderTranslated(record, target);
            }

            @Override protected void done() {
                try {
                    conversationPane.setText(get());
                    conversationPane.setCaretPosition(0);
                    statusLabel.setText("翻译完成：" + target.name);
                } catch (Exception e) {
                    conversationPane.setText(ConversationRenderer.renderTranslationNotice(record, target,
                            "翻译失败：" + e.getMessage() + "。建议稍后重试，或检查当前网络是否可以访问在线翻译接口。"));
                    conversationPane.setCaretPosition(0);
                    statusLabel.setText("翻译失败");
                }
            }
        };
        worker.execute();
    }

    private void clearDetail() {
        loadingRecord = true;
        selected = null;
        bugIdField.setText("");
        subjectField.setText("");
        dateField.setText("");
        fromField.setText("");
        messageIdField.setText("");
        urlField.setText("");
        versionField.setText("");
        statusField.setText("未分析");
        severityField.setText("");
        tagsField.setText("");
        errorArea.setText("");
        reproArea.setText("");
        stepsArea.setText("");
        notesArea.setText("");
        conversationPane.setText(ConversationRenderer.render(new BugRecord()));
        rawArea.setText("");
        loadingRecord = false;
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

    private static JTextArea readOnlyArea(int rows) {
        JTextArea a = area(rows);
        a.setEditable(false);
        a.setBackground(UIManager.getColor("TextField.inactiveBackground"));
        return a;
    }

    private static JEditorPane conversationPane() {
        JEditorPane pane = new JEditorPane();
        pane.setContentType("text/html");
        pane.setEditable(false);
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        pane.setBackground(new Color(245, 247, 250));
        return pane;
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

class ConversationRenderer {
    private static final Pattern JOINED_MAIL = Pattern.compile("(?m)^-----\\s+关联邮件:\\s*(.*?)\\s+-----\\s*$");
    private static final Pattern THREAD_LINE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+from\\s+(.+?)\\s*$");
    private static final String ATTACHMENT_MARK = new String(Character.toChars(0x1F4CE));
    private static final Set<String> HEADER_LABELS = new HashSet<String>(Arrays.asList(
            "From", "To", "Cc", "Subject", "Date", "Message-ID", "Views", "Thread", "Lists"
    ));

    static String render(BugRecord record) {
        return render(record, null, null, null);
    }

    static boolean hasTranslatableBody(BugRecord record) {
        for (MailMessage message : buildMessages(record)) {
            if (message.body != null && !message.body.trim().isEmpty()) return true;
        }
        return false;
    }

    static String renderTranslationNotice(BugRecord record, TranslationLanguage target, String notice) {
        return render(record, null, target, notice);
    }

    static String renderTranslated(BugRecord record, TranslationLanguage target) throws IOException {
        List<MailMessage> messages = buildMessages(record);
        Map<String, String> translations = new HashMap<String, String>();
        for (MailMessage message : messages) {
            String body = compactBody(message.body);
            if (!body.isEmpty()) {
                translations.put(messageKey(message), ProfessionalTranslator.translateBody(body, target));
            }
        }
        return render(record, translations, target, null);
    }

    private static String render(BugRecord record, Map<String, String> translations, TranslationLanguage target, String notice) {
        List<MailMessage> messages = buildMessages(record);
        String reporterKey = reporterKey(messages, record);
        String title = cleanOneLine((record.bugId + " " + record.subject).trim());
        if (title.isEmpty()) title = "未命名邮件";

        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family:Microsoft YaHei,Segoe UI,sans-serif;font-size:12px;");
        html.append("background-color:#f5f7fa;color:#1f2937;margin:0;padding:12px;'>");
        html.append("<table width='100%' cellpadding='0' cellspacing='0'><tr><td>");
        html.append("<div style='font-size:16px;font-weight:bold;color:#111827;margin-bottom:4px;'>")
                .append(escape(title)).append("</div>");
        html.append("<div style='color:#667085;margin-bottom:12px;'>");
        html.append("邮件往返 ").append(messages.size()).append(" 封");
        if (!record.status.trim().isEmpty()) html.append(" · 状态 ").append(escape(record.status.trim()));
        if (!record.pgVersion.trim().isEmpty()) html.append(" · PG ").append(escape(record.pgVersion.trim()));
        if (target != null && translations != null) html.append(" · 已嵌入翻译: ").append(escape(target.name));
        html.append("</div>");
        if (notice != null && !notice.trim().isEmpty()) {
            html.append("<table width='100%' cellpadding='8' cellspacing='0' bgcolor='#fff7cc' ")
                    .append("style='border:1px solid #e3b341;margin-bottom:12px;'><tr><td>")
                    .append(escape(notice)).append("</td></tr></table>");
        }

        if (messages.isEmpty()) {
            html.append(emptyMessage(record.rawText));
        } else {
            for (MailMessage message : messages) {
                appendBubble(html, message, reporterKey, translations);
            }
        }

        html.append("</td></tr></table></body></html>");
        return html.toString();
    }

    static String translationSource(BugRecord record) {
        List<MailMessage> messages = buildMessages(record);
        StringBuilder text = new StringBuilder();
        for (MailMessage message : messages) {
            String body = compactBody(message.body);
            if (body.isEmpty() && message.summaryOnly) continue;
            text.append("Time: ").append(message.date).append('\n');
            text.append("From: ").append(message.from).append('\n');
            if (!message.to.isEmpty()) text.append("To: ").append(message.to).append('\n');
            if (!message.cc.isEmpty()) text.append("Cc: ").append(message.cc).append('\n');
            if (!message.subject.isEmpty()) text.append("Subject: ").append(message.subject).append('\n');
            if (message.hasAttachment) text.append("Attachment: yes\n");
            text.append("Content:\n");
            text.append(body).append("\n\n");
        }
        if (text.length() == 0 && record.rawText != null) {
            text.append(extractBody(record.rawText));
        }
        return text.toString().trim();
    }

    private static void appendBubble(StringBuilder html, MailMessage message, String reporterKey, Map<String, String> translations) {
        String identity = identity(message.from);
        boolean reporterSide = !reporterKey.isEmpty() && reporterKey.equals(identity);
        if (message.from.toLowerCase(Locale.ROOT).contains("pg bug reporting form")) reporterSide = true;
        String align = reporterSide ? "left" : "right";
        String bg = reporterSide ? "#ffffff" : "#d9fdd3";
        String border = reporterSide ? "#d0d7de" : "#9bd88f";
        String role = reporterSide ? "来信 / 问题报告" : "社区回复";

        html.append("<table width='100%' cellpadding='0' cellspacing='0' style='margin:8px 0 12px 0;'>");
        html.append("<tr><td align='").append(align).append("'>");
        html.append("<table width='82%' cellpadding='8' cellspacing='0' bgcolor='").append(bg).append("' ");
        html.append("style='border:1px solid ").append(border).append(";border-radius:8px;'>");
        html.append("<tr><td>");
        html.append("<div style='font-size:13px;font-weight:bold;color:#101828;'>")
                .append(escape(displayName(message.from)));
        html.append(" <span style='font-weight:normal;color:#667085;'>").append(escape(role)).append("</span>");
        if (message.hasAttachment) html.append(" <span style='color:#b54708;'>附件</span>");
        html.append("</div>");
        html.append("<div style='font-size:11px;color:#667085;margin-top:2px;'>");
        if (!message.date.isEmpty()) html.append(escape(message.date));
        if (!message.from.isEmpty()) html.append("<br>发件人: ").append(escape(message.from));
        if (!message.to.isEmpty()) html.append("<br>收件人: ").append(escape(message.to));
        if (!message.cc.isEmpty()) html.append("<br>抄送: ").append(escape(message.cc));
        if (!message.subject.isEmpty()) html.append("<br>主题: ").append(escape(message.subject));
        html.append("</div>");
        if (message.body == null || message.body.trim().isEmpty()) {
            if (message.summaryOnly) {
                html.append("<div style='font-size:11px;color:#98a2b3;margin-top:8px;'>正文未抓取，仅显示线程索引信息。</div>");
            } else {
                html.append("<div style='font-size:11px;color:#98a2b3;margin-top:8px;'>这封邮件没有可显示的正文。</div>");
            }
        } else {
            html.append("<div style='font-family:Microsoft YaHei,Segoe UI,Consolas,monospace;font-size:12px;");
            html.append("line-height:1.45;margin-top:8px;color:#1f2937;'>");
            String translated = translations == null ? "" : translations.get(messageKey(message));
            if (translated != null && !translated.trim().isEmpty()) {
                html.append("<div style='font-size:11px;font-weight:bold;color:#175cd3;margin-bottom:4px;'>译文</div>");
                html.append(bodyHtml(translated, false));
                html.append("<div style='border-top:1px solid #cbd5e1;margin-top:8px;padding-top:8px;color:#667085;'>");
                html.append("<div style='font-size:11px;font-weight:bold;margin-bottom:4px;'>原文</div>");
                html.append(bodyHtml(message.body, false));
                html.append("</div>");
            } else {
                html.append(bodyHtml(message.body, false));
            }
            html.append("</div>");
        }
        html.append("</td></tr></table>");
        html.append("</td></tr></table>");
    }

    private static String emptyMessage(String rawText) {
        String text = rawText == null || rawText.trim().isEmpty()
                ? "当前记录没有邮件原文。"
                : rawText.trim();
        return "<table width='100%' cellpadding='10' cellspacing='0' bgcolor='#ffffff' "
                + "style='border:1px solid #d0d7de;'><tr><td>"
                + bodyHtml(limit(text, 4000), false)
                + "</td></tr></table>";
    }

    private static List<MailMessage> buildMessages(BugRecord record) {
        List<MailMessage> thread = parseThread(record.rawText);
        List<MailMessage> bodies = parseBodyBlocks(record.rawText);
        LinkedHashMap<String, MailMessage> merged = new LinkedHashMap<String, MailMessage>();

        for (MailMessage message : thread) {
            merged.put(looseKey(message), message);
        }
        for (MailMessage body : bodies) {
            String key = looseKey(body);
            MailMessage existing = merged.get(key);
            if (existing == null) {
                merged.put(key, body);
            } else {
                existing.merge(body);
            }
        }

        List<MailMessage> result = new ArrayList<MailMessage>(merged.values());
        if (result.isEmpty() && record.rawText != null && !record.rawText.trim().isEmpty()) {
            MailMessage fallback = new MailMessage();
            fallback.from = record.from;
            fallback.date = record.date;
            fallback.subject = record.subject;
            fallback.messageId = record.messageId;
            fallback.body = extractBody(record.rawText);
            result.add(fallback);
        }

        Collections.sort(result, new Comparator<MailMessage>() {
            @Override public int compare(MailMessage a, MailMessage b) {
                return a.date.compareTo(b.date);
            }
        });
        return result;
    }

    private static List<MailMessage> parseBodyBlocks(String rawText) {
        List<MailBlock> blocks = splitBlocks(rawText);
        List<MailMessage> messages = new ArrayList<MailMessage>();
        for (MailBlock block : blocks) {
            MailMessage message = parseBodyBlock(block.text);
            applySeparatorHints(message, block.title);
            if (!message.date.isEmpty() || !message.from.isEmpty() || !message.body.isEmpty()) {
                messages.add(message);
            }
        }
        return messages;
    }

    private static List<MailBlock> splitBlocks(String rawText) {
        List<MailBlock> blocks = new ArrayList<MailBlock>();
        if (rawText == null || rawText.trim().isEmpty()) return blocks;
        Matcher matcher = JOINED_MAIL.matcher(rawText);
        int start = 0;
        String title = "";
        while (matcher.find()) {
            addBlock(blocks, title, rawText.substring(start, matcher.start()));
            title = matcher.group(1);
            start = matcher.end();
        }
        addBlock(blocks, title, rawText.substring(start));
        return blocks;
    }

    private static void addBlock(List<MailBlock> blocks, String title, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (!trimmed.isEmpty()) blocks.add(new MailBlock(title == null ? "" : title.trim(), trimmed));
    }

    private static MailMessage parseBodyBlock(String block) {
        MailMessage message = new MailMessage();
        message.from = headerValue(block, "From");
        message.to = headerValue(block, "To");
        message.cc = headerValue(block, "Cc");
        message.subject = headerValue(block, "Subject");
        message.date = headerValue(block, "Date");
        message.messageId = headerValue(block, "Message-ID");
        message.body = extractBody(block);
        message.hasAttachment = block.contains(ATTACHMENT_MARK)
                || block.toLowerCase(Locale.ROOT).contains("attachment");
        return message;
    }

    private static void applySeparatorHints(MailMessage message, String title) {
        if (title == null || title.trim().isEmpty()) return;
        Matcher matcher = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+(.+?)\\s+([^\\s]+@[^\\s]+)$").matcher(title.trim());
        if (matcher.find()) {
            if (message.date.isEmpty()) message.date = matcher.group(1).trim();
            if (message.from.isEmpty()) message.from = matcher.group(2).trim();
            if (message.messageId.isEmpty()) message.messageId = matcher.group(3).trim();
        }
    }

    private static List<MailMessage> parseThread(String rawText) {
        List<MailMessage> messages = new ArrayList<MailMessage>();
        if (rawText == null || rawText.trim().isEmpty()) return messages;
        String[] lines = normalizeLines(rawText);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if ("Thread:".equalsIgnoreCase(lines[i].trim())) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) return messages;
        for (int i = start; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if ("Lists:".equalsIgnoreCase(line) || isArchiveStop(line)) break;
            Matcher matcher = THREAD_LINE.matcher(line);
            if (!matcher.find()) {
                if (!messages.isEmpty()) break;
                continue;
            }
            String sender = matcher.group(2).replace(ATTACHMENT_MARK, "").trim();
            MailMessage message = new MailMessage();
            message.date = matcher.group(1).trim();
            message.from = sender;
            message.summaryOnly = true;
            message.hasAttachment = line.contains(ATTACHMENT_MARK);
            messages.add(message);
        }
        return messages;
    }

    private static String extractBody(String block) {
        String[] lines = normalizeLines(block);
        int start = bodyStart(lines);
        StringBuilder body = new StringBuilder();
        boolean seenContent = false;
        for (int i = start; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (isArchiveStop(trimmed)) break;
            if (isQuoteLine(trimmed) || isQuoteBoundary(trimmed)) {
                if (seenContent) break;
                continue;
            }
            if (body.length() == 0 && isLeadingNoise(trimmed)) continue;
            body.append(lines[i]).append('\n');
            if (!trimmed.isEmpty()) seenContent = true;
        }
        return compactBody(body.toString());
    }

    private static int bodyStart(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if ("Lists:".equalsIgnoreCase(lines[i].trim())) {
                int j = i + 1;
                while (j < lines.length) {
                    String t = lines[j].trim();
                    if (!t.isEmpty() && !t.matches("pgsql-[A-Za-z0-9_-]+")) break;
                    j++;
                }
                return j;
            }
        }
        for (int i = 0; i < lines.length; i++) {
            if ("Thread:".equalsIgnoreCase(lines[i].trim())) {
                int j = i + 1;
                while (j < lines.length) {
                    String t = lines[j].trim();
                    if (t.isEmpty() || THREAD_LINE.matcher(t).find()) {
                        j++;
                        continue;
                    }
                    break;
                }
                return j;
            }
        }
        return 0;
    }

    private static String[] normalizeLines(String text) {
        return (text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n')).split("\\n", -1);
    }

    private static String headerValue(String block, String label) {
        String[] lines = normalizeLines(block);
        String prefix = label + ":";
        StringBuilder value = new StringBuilder();
        boolean capture = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!capture) {
                if (trimmed.equalsIgnoreCase(label) || trimmed.equalsIgnoreCase(prefix)) {
                    capture = true;
                    continue;
                }
                if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    capture = true;
                    String rest = trimmed.substring(prefix.length()).trim();
                    if (!rest.isEmpty()) value.append(rest);
                    continue;
                }
            } else {
                if (isKnownHeader(trimmed) || isArchiveStop(trimmed)) break;
                if (!trimmed.isEmpty()) {
                    if (value.length() > 0) value.append(' ');
                    value.append(trimmed);
                }
            }
        }
        return cleanOneLine(value.toString());
    }

    private static boolean isKnownHeader(String line) {
        if (line == null || line.trim().isEmpty()) return false;
        String t = line.trim();
        for (String label : HEADER_LABELS) {
            if (t.equalsIgnoreCase(label) || t.equalsIgnoreCase(label + ":")) return true;
            if (t.regionMatches(true, 0, label + ":", 0, label.length() + 1)) return true;
        }
        return false;
    }

    private static boolean isLeadingNoise(String line) {
        return line.equals("Home")
                || line.equals("About")
                || line.equals("Download")
                || line.equals("Documentation")
                || line.equals("Community")
                || line.equals("Developers")
                || line.equals("Support")
                || line.equals("Donate")
                || line.equals("Your account")
                || line.equals("Quick Links")
                || line.equals("Contributors")
                || line.equals("Mailing Lists")
                || line.equals("IRC")
                || line.equals("Local User Groups")
                || line.equals("Events")
                || line.equals("International Sites");
    }

    private static boolean isArchiveStop(String line) {
        if (line == null || line.isEmpty()) return false;
        return line.equals("In response to")
                || line.equals("Responses")
                || line.startsWith("Browse pgsql-")
                || line.equals("Previous Message")
                || line.equals("Next Message")
                || line.startsWith("Privacy Policy")
                || line.startsWith("Code of Conduct")
                || line.startsWith("About PostgreSQL")
                || line.equals("Contact")
                || line.startsWith("Copyright ");
    }

    private static boolean isQuoteLine(String line) {
        return line != null && line.trim().startsWith(">");
    }

    private static boolean isQuoteBoundary(String line) {
        if (line == null || line.trim().isEmpty()) return false;
        String t = line.trim();
        return t.matches("(?i)^on .+ wrote:$")
                || t.matches("(?i)^.*<[^>]+> wrote:$")
                || t.matches("(?i)^-+\\s*(original|forwarded) message\\s*-+")
                || t.matches("(?i)^in response to\\b.*")
                || t.matches("(?i)^responses\\b.*");
    }

    private static String bodyHtml(String body, boolean summaryOnly) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) {
            text = summaryOnly ? "这封只在邮件 Thread 索引里出现，本地还没有抓到正文。" : "这封邮件没有可显示的正文。";
        }
        text = compactBody(text);
        StringBuilder html = new StringBuilder();
        String[] lines = normalizeLines(text);
        for (String line : lines) {
            String trimmed = line.trim();
            if (isQuoteLine(trimmed) || isQuoteBoundary(trimmed)) break;
            String visible = preserveLeadingSpaces(line);
            if (trimmed.startsWith(">")) {
                html.append("<span style='color:#667085;'>").append(visible).append("</span><br>");
            } else if (trimmed.matches("(?i)^on .+ wrote:$")) {
                html.append("<span style='color:#667085;font-weight:bold;'>").append(visible).append("</span><br>");
            } else {
                html.append(visible).append("<br>");
            }
        }
        return html.toString();
    }

    private static String preserveLeadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ' && count < 16) count++;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append("&nbsp;");
        sb.append(escape(line.substring(count)));
        return sb.toString();
    }

    private static String compactBody(String body) {
        String text = body == null ? "" : body.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replaceAll("(?m)[ \\t]+$", "");
        text = text.replaceAll("\\n{4,}", "\n\n\n");
        return text.trim();
    }

    private static String reporterKey(List<MailMessage> messages, BugRecord record) {
        for (MailMessage message : messages) {
            String sender = message.from.toLowerCase(Locale.ROOT);
            if (sender.contains("pg bug reporting form") || sender.contains("noreply")) continue;
            String key = identity(message.from);
            if (!key.isEmpty()) return key;
        }
        String recordKey = identity(record.from);
        if (!recordKey.isEmpty()) return recordKey;
        return messages.isEmpty() ? "" : identity(messages.get(0).from);
    }

    private static String looseKey(MailMessage message) {
        String identity = identity(message.from);
        if (!message.date.isEmpty() || !identity.isEmpty()) return message.date + "|" + identity;
        return "msg:" + message.messageId;
    }

    private static String messageKey(MailMessage message) {
        return looseKey(message);
    }

    private static String identity(String sender) {
        String email = email(sender).toLowerCase(Locale.ROOT);
        if (!email.isEmpty()) return email;
        return displayName(sender).toLowerCase(Locale.ROOT);
    }

    private static String displayName(String sender) {
        String s = cleanOneLine(sender);
        int lt = s.indexOf('<');
        if (lt > 0) s = s.substring(0, lt).trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) s = s.substring(1, s.length() - 1);
        return s.isEmpty() ? "未知发件人" : s;
    }

    private static String email(String sender) {
        Matcher matcher = Pattern.compile("<([^>]+)>").matcher(sender == null ? "" : sender);
        return matcher.find() ? cleanOneLine(matcher.group(1)) : "";
    }

    private static String cleanOneLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String limit(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, max) + "\n\n...";
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private static class MailBlock {
        final String title;
        final String text;
        MailBlock(String title, String text) {
            this.title = title;
            this.text = text;
        }
    }

    private static class MailMessage {
        String date = "";
        String from = "";
        String to = "";
        String cc = "";
        String subject = "";
        String messageId = "";
        String body = "";
        boolean hasAttachment;
        boolean summaryOnly;

        void merge(MailMessage other) {
            if (other == null) return;
            if (from.isEmpty()) from = other.from;
            if (to.isEmpty()) to = other.to;
            if (cc.isEmpty()) cc = other.cc;
            if (subject.isEmpty()) subject = other.subject;
            if (messageId.isEmpty()) messageId = other.messageId;
            if (body.isEmpty() && !other.body.isEmpty()) {
                body = other.body;
                summaryOnly = false;
            }
            hasAttachment = hasAttachment || other.hasAttachment;
        }
    }
}

class TranslationLanguage {
    final String name;
    final String code;

    TranslationLanguage(String name, String code) {
        this.name = name;
        this.code = code;
    }

    @Override public String toString() {
        return name;
    }
}

class ProfessionalTranslator {
    private static final Pattern SQL_START = Pattern.compile(
            "(?i)^\\s*(?:[-*]\\s*)?(?:postgres(?:=#|=>)\\s*)?(CREATE|SELECT|INSERT|UPDATE|DELETE|ALTER|DROP|WITH|BEGIN|COMMIT|ROLLBACK|EXPLAIN|LOAD|SET|RESET|COPY|VACUUM|ANALYZE|TRUNCATE|PREPARE|EXECUTE|DO|CALL)\\b.*");
    private static final Pattern LOG_LINE = Pattern.compile(
            "(?i)^\\s*(ERROR|FATAL|PANIC|WARNING|NOTICE|DETAIL|HINT|CONTEXT|LOCATION|STATEMENT):\\s+.*");
    private static final Pattern PROTECTED_TOKEN = Pattern.compile(
            "`[^`]+`"
                    + "|https?://\\S+"
                    + "|[A-Za-z]:\\\\\\S+"
                    + "|/\\S+"
                    + "|<[^>]+>"
                    + "|#[0-9]+"
                    + "|\\b[A-Za-z0-9._%+-]+(?:@|\\(at\\))[A-Za-z0-9._%+()\\-]+\\b"
                    + "|\\b[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*\\(\\)"
                    + "|\\b[A-Za-z_][A-Za-z0-9_]*_[A-Za-z0-9_]+\\b"
                    + "|\\b(?:PostgreSQL|Postgres|SQL|DDL|DML|DCL|TCL|WAL|LSN|MVCC|VACUUM|ANALYZE|GIN|GiST|SP-GiST|BRIN|B-tree|hash index|planner|executor|optimizer|parser|rewriter|backpatch|buildfarm|snapshot|serializable|relation|tuple|page|buffer|buffer lock|checkpoint|autovacuum|replication|logical replication|physical replication|standby|primary|failover|timeline|extension|fdw|opclass|collation|ICU|OID|TOAST|NULL|SQLSTATE|SIGSEGV|core dump|backtrace|pg_basebackup|pg_dump|pg_restore|pg_upgrade|psql|initdb|pgbench|pgsql-bugs|pgsql-hackers|odbc_fdw|byteaout|float8out|ERROR|FATAL|PANIC|WARNING|NOTICE|HINT|DETAIL|CONTEXT)\\b",
            Pattern.CASE_INSENSITIVE);

    static TranslationLanguage[] languages() {
        return new TranslationLanguage[] {
                new TranslationLanguage("中文（简体）", "zh-CN"),
                new TranslationLanguage("English", "en"),
                new TranslationLanguage("日本語", "ja"),
                new TranslationLanguage("한국어", "ko"),
                new TranslationLanguage("Français", "fr"),
                new TranslationLanguage("Deutsch", "de"),
                new TranslationLanguage("Español", "es"),
                new TranslationLanguage("Русский", "ru"),
                new TranslationLanguage("Português", "pt"),
                new TranslationLanguage("Tiếng Việt", "vi")
        };
    }

    static String translate(String source, TranslationLanguage target) throws IOException {
        if (target == null) target = languages()[0];
        StringBuilder out = new StringBuilder();
        out.append("目标语言: ").append(target.name).append('\n');
        out.append("说明: SQL、代码、路径、日志、错误码、函数名和 PostgreSQL 专业术语会尽量保留原文。\n\n");
        out.append(translateBody(source, target));
        return out.toString().replaceAll("\\n{4,}", "\n\n\n").trim();
    }

    static String translateBody(String source, TranslationLanguage target) throws IOException {
        if (target == null) target = languages()[0];
        StringBuilder out = new StringBuilder();
        String[] lines = normalizeLines(source);
        StringBuilder paragraph = new StringBuilder();
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                flushParagraph(out, paragraph, target);
                out.append('\n');
            } else if (isCodeLikeLine(line)) {
                flushParagraph(out, paragraph, target);
                out.append(line).append('\n');
            } else {
                if (paragraph.length() > 0) paragraph.append('\n');
                paragraph.append(line);
            }
        }
        flushParagraph(out, paragraph, target);
        return out.toString().replaceAll("\\n{4,}", "\n\n\n").trim();
    }

    private static void flushParagraph(StringBuilder out, StringBuilder paragraph, TranslationLanguage target) throws IOException {
        String text = paragraph.toString().trim();
        paragraph.setLength(0);
        if (text.isEmpty()) return;

        ProtectedText protectedText = protectTerms(text);
        String translated = translateLarge(protectedText.text, target.code);
        out.append(protectedText.restore(translated)).append("\n\n");
    }

    private static String translateLarge(String text, String targetCode) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String chunk : chunks(text, 1400)) {
            if (out.length() > 0) out.append('\n');
            out.append(translateChunk(chunk, targetCode));
        }
        return out.toString();
    }

    private static String translateChunk(String text, String targetCode) throws IOException {
        String query = URLEncoder.encode(text, "UTF-8");
        String target = URLEncoder.encode(targetCode, "UTF-8");
        URL url = new URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
                + target + "&dt=t&q=" + query);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(45000);
        conn.setRequestProperty("User-Agent", "PG-Bug-Mail-Tracker/1.0");
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = in == null ? "" : readAll(in);
        if (code >= 400) throw new IOException("HTTP " + code + "\n" + body);
        return parseGoogleTranslation(body);
    }

    private static String parseGoogleTranslation(String json) throws IOException {
        try {
            ScriptEngine engine = new ScriptEngineManager().getEngineByName("javascript");
            if (engine != null) {
                Object rootObject = engine.eval("Java.asJSONCompatible(" + json + ")");
                if (rootObject instanceof java.util.List) {
                    java.util.List root = (java.util.List) rootObject;
                    if (!root.isEmpty() && root.get(0) instanceof java.util.List) {
                        java.util.List sentences = (java.util.List) root.get(0);
                        StringBuilder out = new StringBuilder();
                        for (Object item : sentences) {
                            if (item instanceof java.util.List) {
                                java.util.List sentence = (java.util.List) item;
                                if (!sentence.isEmpty() && sentence.get(0) != null) out.append(sentence.get(0).toString());
                            }
                        }
                        if (out.length() > 0) return out.toString();
                    }
                }
            }
        } catch (Exception ignored) {
            // Some Java runtimes remove Nashorn; use the small fallback parser below.
        }

        String fallback = parseTranslationFallback(json);
        if (!fallback.isEmpty()) return fallback;
        throw new IOException("无法解析翻译接口返回内容");
    }

    private static String parseTranslationFallback(String json) {
        List<String> strings = new ArrayList<String>();
        boolean inString = false;
        boolean escaped = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (!inString) {
                if (c == '"') {
                    inString = true;
                    current.setLength(0);
                }
                continue;
            }
            if (escaped) {
                if (c == 'n') current.append('\n');
                else if (c == 't') current.append('\t');
                else if (c == 'r') current.append('\r');
                else if (c == 'u' && i + 4 < json.length()) {
                    try {
                        current.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        i += 4;
                    } catch (NumberFormatException ex) {
                        current.append("\\u");
                    }
                } else {
                    current.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                strings.add(current.toString());
                inString = false;
            } else {
                current.append(c);
            }
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i + 1 < strings.size(); i += 2) {
            out.append(strings.get(i));
        }
        return out.toString();
    }

    private static ProtectedText protectTerms(String text) {
        Matcher matcher = PROTECTED_TOKEN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        List<String> terms = new ArrayList<String>();
        while (matcher.find()) {
            String term = matcher.group();
            String marker = "ZXPGTERM" + terms.size() + "ZX";
            terms.add(term);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(marker));
        }
        matcher.appendTail(buffer);
        return new ProtectedText(buffer.toString(), terms);
    }

    private static boolean isCodeLikeLine(String line) {
        String t = line == null ? "" : line.trim();
        if (t.isEmpty()) return false;
        if (SQL_START.matcher(t).matches() || LOG_LINE.matcher(t).matches()) return true;
        if (t.startsWith(">")) return true;
        if (t.startsWith("$ ") || t.startsWith("# ") || t.startsWith("./") || t.startsWith("/") || t.matches("^[A-Za-z]:\\\\.*")) return true;
        if (t.matches(".*\\b[a-zA-Z_][a-zA-Z0-9_]*\\(.*\\).*")) return true;
        if (t.matches(".*(::|:=|=>|->|\\{|\\}|\\[|\\]|;).*")
                && t.matches(".*\\b(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|ERROR|FATAL|PANIC|NULL|return|if|else|for|while)\\b.*")) {
            return true;
        }
        int codeChars = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ("{}[]();=<>|/$\\".indexOf(c) >= 0) codeChars++;
        }
        return codeChars >= 4 && t.indexOf(' ') < 0;
    }

    private static List<String> chunks(String text, int max) {
        List<String> chunks = new ArrayList<String>();
        String remaining = text == null ? "" : text.trim();
        while (remaining.length() > max) {
            int split = bestSplit(remaining, max);
            chunks.add(remaining.substring(0, split).trim());
            remaining = remaining.substring(split).trim();
        }
        if (!remaining.isEmpty()) chunks.add(remaining);
        return chunks;
    }

    private static int bestSplit(String text, int max) {
        int split = -1;
        for (String mark : new String[] {"\n\n", ". ", "? ", "! ", "; ", "\n", ", "}) {
            split = text.lastIndexOf(mark, max);
            if (split > max / 2) return split + mark.length();
        }
        return max;
    }

    private static String[] normalizeLines(String text) {
        return (text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n')).split("\\n", -1);
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        try {
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        } finally {
            in.close();
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static class ProtectedText {
        final String text;
        final List<String> terms;

        ProtectedText(String text, List<String> terms) {
            this.text = text;
            this.terms = terms;
        }

        String restore(String value) {
            String restored = value == null ? "" : value;
            for (int i = 0; i < terms.size(); i++) {
                String marker = "ZXPGTERM" + i + "ZX";
                restored = restored.replace(marker, terms.get(i));
                restored = restored.replace(marker.toLowerCase(Locale.ROOT), terms.get(i));
                restored = restored.replace(marker.replace("ZX", "ZX "), terms.get(i));
            }
            return restored;
        }
    }
}

class BugRecord {
    boolean checked;
    String bugId = "";
    String messageId = "";
    String threadKey = "";
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
    private final String[] columns = {"选择", "Bug 编号", "日期", "主题", "PG 版本", "状态", "严重级别"};
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
                n.bugId = first(n.bugId, Extractor.extractBugIdForRecord(n));
                all.add(n);
                byId.put(k, n);
                changed++;
            } else {
                old.bugId = first(old.bugId, n.bugId);
                old.threadKey = first(old.threadKey, n.threadKey);
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
        normalizeIdentifiers(r);
        String bugId = r.bugId == null ? "" : r.bugId.trim();
        if (!bugId.isEmpty()) return "bug:" + bugId;
        String threadKey = r.threadKey == null ? "" : r.threadKey.trim();
        if (!threadKey.isEmpty()) return "thread:" + threadKey;
        return r.messageId == null || r.messageId.trim().isEmpty() ? "url:" + r.url : "msg:" + r.messageId;
    }

    private static void normalizeIdentifiers(BugRecord r) {
        if (r == null) return;
        if (r.bugId == null || r.bugId.trim().isEmpty()) {
            r.bugId = Extractor.extractBugIdForRecord(r);
        }
        if (r.threadKey == null || r.threadKey.trim().isEmpty()) {
            r.threadKey = Extractor.threadKey(r);
        }
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

    public List<BugRecord> checkedRecords() {
        List<BugRecord> records = new ArrayList<BugRecord>();
        for (BugRecord r : all) {
            if (r.checked) records.add(r);
        }
        return records;
    }

    public boolean removeRecord(BugRecord record) {
        return removeRecords(Collections.singletonList(record)) > 0;
    }

    public int removeRecords(Collection<BugRecord> records) {
        if (records == null || records.isEmpty()) return 0;
        Set<BugRecord> identities = Collections.newSetFromMap(new IdentityHashMap<BugRecord, Boolean>());
        Set<String> keys = new HashSet<String>();
        for (BugRecord record : records) {
            if (record == null) continue;
            identities.add(record);
            keys.add(key(record));
        }

        int removed = 0;
        Iterator<BugRecord> iterator = all.iterator();
        while (iterator.hasNext()) {
            BugRecord current = iterator.next();
            if (identities.contains(current) || keys.contains(key(current))) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) applyFilter();
        return removed;
    }

    public BugRecord getRecord(int row) { return shown.get(row); }
    @Override public int getRowCount() { return shown.size(); }
    @Override public int getColumnCount() { return columns.length; }
    @Override public String getColumnName(int column) { return columns[column]; }
    @Override public Class<?> getColumnClass(int columnIndex) {
        return columnIndex == 0 ? Boolean.class : String.class;
    }
    @Override public boolean isCellEditable(int rowIndex, int columnIndex) {
        return columnIndex == 0;
    }
    @Override public void setValueAt(Object value, int rowIndex, int columnIndex) {
        if (columnIndex != 0 || rowIndex < 0 || rowIndex >= shown.size()) return;
        shown.get(rowIndex).checked = Boolean.TRUE.equals(value);
        fireTableCellUpdated(rowIndex, columnIndex);
    }
    @Override public Object getValueAt(int rowIndex, int columnIndex) {
        BugRecord r = shown.get(rowIndex);
        switch (columnIndex) {
            case 0: return r.checked;
            case 1: return r.bugId;
            case 2: return r.date;
            case 3: return r.subject;
            case 4: return r.pgVersion;
            case 5: return r.status;
            case 6: return r.severity;
            default: return "";
        }
    }
}

class PgArchiveClient {
    private static final String BASE = "https://www.postgresql.org";
    private static final int MAX_THREAD_MESSAGES = 80;
    private static final Pattern LINK = Pattern.compile("<a\\s+[^>]*href=\"([^\"]*/message-id/[^\"]+)\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern THREAD_SELECT = Pattern.compile("<select[^>]*id=\"thread_select\"[^>]*>(.*?)</select>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern THREAD_OPTION = Pattern.compile("<option\\s+[^>]*value=\"([^\"]+)\"[^>]*>(.*?)</option>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private final Set<String> expandedThreads = new HashSet<String>();

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
        return fetchMessage(url, fallbackSubject, true);
    }

    private BugRecord fetchMessage(String url, String fallbackSubject, boolean includeThread) throws IOException {
        String html = get(url);
        String text = htmlToText(html);
        BugRecord r = new BugRecord();
        r.url = url;
        r.subject = first(match(text, "(?m)^Subject:\\s*(.+)$"), first(match(text, "(?m)^#\\s*(.+)$"), fallbackSubject));
        r.bugId = Extractor.extractBugIdFromMessage(r.subject, text);
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
        r.threadKey = Extractor.threadKey(r);
        if (includeThread) expandThreadMessages(r, html);
        return r;
    }

    private void expandThreadMessages(BugRecord baseRecord, String html) throws IOException {
        String expandKey = first(baseRecord.threadKey, first(baseRecord.messageId, archiveUrlKey(baseRecord.url)));
        if (expandKey.isEmpty() || !expandedThreads.add(expandKey)) return;

        List<String> links = threadMessageLinks(html, baseRecord.url);
        for (String link : links) {
            try {
                BugRecord related = fetchMessage(link, baseRecord.subject, false);
                if (related.threadKey.trim().isEmpty()) related.threadKey = baseRecord.threadKey;
                mergeThreadRecord(baseRecord, related);
                Thread.sleep(80L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("线程正文抓取被中断", e);
            } catch (IOException ignored) {
                // Individual archive messages can disappear or fail; keep the rest of the thread usable.
            }
        }
    }

    private static List<String> threadMessageLinks(String html, String currentUrl) {
        LinkedHashMap<String, String> links = new LinkedHashMap<String, String>();
        Matcher select = THREAD_SELECT.matcher(html);
        if (!select.find()) return new ArrayList<String>();
        Matcher matcher = THREAD_OPTION.matcher(select.group(1));
        while (matcher.find() && links.size() < MAX_THREAD_MESSAGES) {
            String href = BASE + "/message-id/" + decodeEntities(matcher.group(1)).trim();
            if (sameArchiveUrl(href, currentUrl)) continue;
            links.put(archiveUrlKey(href), href);
        }
        return new ArrayList<String>(links.values());
    }

    private static void mergeThreadRecord(BugRecord base, BugRecord incoming) {
        if (incoming == null) return;
        if (base.bugId.trim().isEmpty() && base.threadKey.trim().isEmpty()) {
            base.bugId = incoming.bugId;
        }
        base.threadKey = first(base.threadKey, incoming.threadKey);
        base.subject = preferBugSubject(base.subject, incoming.subject);
        if (base.from.trim().isEmpty()) base.from = incoming.from;
        if (!incoming.date.trim().isEmpty() && (base.date.trim().isEmpty() || incoming.date.compareTo(base.date) < 0)) {
            base.date = incoming.date;
        }
        base.messageId = appendUnique(base.messageId, incoming.messageId);
        base.rawText = appendMailText(base.rawText, incoming);
        base.pgVersion = first(base.pgVersion, incoming.pgVersion);
        base.errorInfo = appendUniqueBlock(base.errorInfo, incoming.errorInfo);
        base.reproCode = appendUniqueBlock(base.reproCode, incoming.reproCode);
        base.diagnosticSteps = first(base.diagnosticSteps, incoming.diagnosticSteps);
        base.updatedAt = PgBugMailTrackerHelper.now();
    }

    private static String appendMailText(String oldText, BugRecord incoming) {
        String body = incoming.rawText == null ? "" : incoming.rawText.trim();
        if (body.isEmpty()) return oldText == null ? "" : oldText;
        if (oldText == null || oldText.trim().isEmpty()) return body;
        if (oldText.contains(body)) return oldText;
        String title = incoming.date + " " + incoming.from + " " + incoming.messageId;
        return oldText + "\n\n----- 关联邮件: " + title.trim() + " -----\n" + body;
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

    private static String preferBugSubject(String oldSubject, String newSubject) {
        if (oldSubject == null || oldSubject.trim().isEmpty()) return first(newSubject, "");
        if (newSubject == null || newSubject.trim().isEmpty()) return oldSubject;
        boolean oldReply = oldSubject.toLowerCase(Locale.ROOT).startsWith("re:");
        boolean newReply = newSubject.toLowerCase(Locale.ROOT).startsWith("re:");
        return oldReply && !newReply ? newSubject : oldSubject;
    }

    private static String absoluteMessageUrl(String href) {
        String value = decodeEntities(href == null ? "" : href.trim());
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        if (value.startsWith("/")) return BASE + value;
        return BASE + "/" + value;
    }

    private static boolean sameArchiveUrl(String a, String b) {
        return archiveUrlKey(a).equals(archiveUrlKey(b));
    }

    private static String archiveUrlKey(String url) {
        String value = url == null ? "" : url.trim();
        int hash = value.indexOf('#');
        if (hash >= 0) value = value.substring(0, hash);
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        if (value.startsWith(BASE)) value = value.substring(BASE.length());
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private String get(String url) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return getOnce(url);
            } catch (IOException e) {
                last = e;
                if (attempt == 3) break;
                try {
                    Thread.sleep(300L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("下载被中断", interrupted);
                }
            }
        }
        throw last;
    }

    private String getOnce(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "PG-Bug-Mail-Tracker/1.0");
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
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
    private static final Pattern THREAD_LINE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+from\\s+(.+)$", Pattern.CASE_INSENSITIVE);
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

    static String extractBugIdForRecord(BugRecord record) {
        if (record == null) return "";
        return extractBugIdFromMessage(record.subject, record.rawText);
    }

    static String extractBugIdFromMessage(String subject, String text) {
        return extractBugId(first(subject, first(headerValue(text, "Subject"), firstHeading(text))));
    }

    static String threadKey(BugRecord record) {
        if (record == null) return "";
        String bugId = first(record.bugId, extractBugIdForRecord(record));
        if (!bugId.isEmpty()) return "";

        String subject = normalizeThreadSubject(first(record.subject, headerValue(record.rawText, "Subject")));
        String root = firstThreadRoot(record.rawText);
        if (!subject.isEmpty() && !root.isEmpty()) return "subject:" + subject + "|root:" + root;
        if (!subject.isEmpty()) return "subject:" + subject;
        if (!root.isEmpty()) return "root:" + root;
        return "";
    }

    static String normalizeThreadSubject(String subject) {
        String s = subject == null ? "" : subject.trim();
        s = s.replaceAll("(?i)^\\s*(\\[[^\\]]*\\]\\s*)+", "");
        boolean changed;
        do {
            String before = s;
            s = s.replaceAll("(?i)^\\s*(re|fw|fwd|aw|回复|答复)\\s*[:：]\\s*", "");
            changed = !before.equals(s);
        } while (changed);
        s = s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        return s;
    }

    private static String firstThreadRoot(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\\n", -1);
        boolean inThread = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (isArchiveStop(trimmed)) break;
            if ("Thread:".equalsIgnoreCase(trimmed)) {
                inThread = true;
                continue;
            }
            if (!inThread) continue;
            if (trimmed.isEmpty()) continue;
            if ("Lists:".equalsIgnoreCase(trimmed) || trimmed.endsWith(":")) break;
            Matcher matcher = THREAD_LINE.matcher(trimmed);
            if (matcher.find()) {
                return clean(matcher.group(1) + " " + matcher.group(2).replace("📎", ""));
            }
        }
        return "";
    }

    private static String withoutThreadIndex(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inThread = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if ("Thread:".equalsIgnoreCase(trimmed)) {
                inThread = true;
                continue;
            }
            if (inThread) {
                if ("Lists:".equalsIgnoreCase(trimmed)) {
                    inThread = false;
                    out.append(line).append('\n');
                }
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static boolean isArchiveStop(String line) {
        String s = line == null ? "" : line.trim().toLowerCase(Locale.ROOT);
        return s.equals("browse pgsql-bugs by date")
                || s.equals("previous message")
                || s.equals("next message")
                || s.equals("privacy policy")
                || s.equals("code of conduct")
                || s.equals("about postgresql")
                || s.equals("contact")
                || s.startsWith("copyright ");
    }

    private static String headerValue(String text, String label) {
        if (text == null || text.trim().isEmpty()) return "";
        Matcher matcher = Pattern.compile("(?im)^" + Pattern.quote(label) + ":\\s*(.+)$").matcher(text);
        return matcher.find() ? clean(matcher.group(1)) : "";
    }

    private static String firstHeading(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        Matcher matcher = Pattern.compile("(?m)^#\\s*(.+)$").matcher(text);
        return matcher.find() ? clean(matcher.group(1)) : "";
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

    private static String first(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a : b;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}

class RecordStore {
    private static final String[] FIELDS = {
            "bugId", "messageId", "threadKey", "subject", "from", "date", "url", "pgVersion", "errorInfo", "reproCode",
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
                if (r.bugId.trim().isEmpty()) r.bugId = Extractor.extractBugIdForRecord(r);
                if (r.threadKey.trim().isEmpty()) r.threadKey = Extractor.threadKey(r);
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
        if ("threadKey".equals(field)) return r.threadKey;
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
        else if ("threadKey".equals(field)) r.threadKey = value;
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
