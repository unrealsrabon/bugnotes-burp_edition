package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.Range;
import burp.api.montoya.core.Registration;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.Highlighter;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;


/**
 * BugNotes — native Burp Suite Swing extension for organized Markdown notes.
 *
 * Uses the Montoya API + Java Swing. Notes are pure Markdown text buffers
 * persisted through {@code api.persistence().extensionData()} so they live
 * inside the active Burp project (.burp) or die with a temporary project.
 *
 * The writing canvas is a native Swing {@link JTextArea}. Native Swing gives us
 * full, deterministic control over the caret ({@code setCaretPosition}) and
 * document offsets, which the Montoya {@code RawEditor} does not expose.
 *
 * Theming: every standard Swing control is a stock component with NO custom
 * painting and NO hardcoded colors — Burp fully dictates their appearance via
 * {@code api.userInterface().applyThemeToComponent}. The only Graphics2D-painted
 * surfaces are the editor gutter and the active-line band, which Burp's theming
 * cannot reach; those derive their colors from the editor's post-theme
 * background so they still track Light/Dark automatically.
 */
public class BugNotesBurp implements BurpExtension {

    private static final String EXT_NAME = "BugNotes";
    private static final String STORE_KEY = "bugnotes.v3.store";
    // Persistence format version tag written as the first line of the blob. Lets
    // future loaders migrate or reject unknown layouts instead of silently
    // corrupting notes (audit C1). Bump when the record layout changes.
    private static final String STORE_VERSION = "BUGNOTES-V1";
    // Debounce window (ms) for the editor-driven autosave (audit C3). Batches a
    // burst of keystrokes into one persist so we never thrash the project store.
    private static final int AUTOSAVE_DEBOUNCE_MS = 800;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final AtomicLong ID_SEQ = new AtomicLong(System.currentTimeMillis());

    // A single neutral tone for secondary text labels; theme-agnostic mid-grey
    // reads acceptably on both light and dark. No component backgrounds use it.
    private static final Color MUTED_FG = new Color(140, 140, 145);


    private MontoyaApi api;
    private PersistedObject store;

    // UI
    private JPanel root;
    private DefaultListModel<Note> listModel;
    private JList<Note> notesList;
    private JTextField searchField;
    private JTextField titleField;
    private JLabel statusLabel;
    private CardLayout canvasCards;
    private JPanel canvasHolder;
    // The top action strip (title + toolbar) and the bottom find bar. Both are
    // hidden while no note is open so the placeholder overlay stands alone.
    private JComponent topBar;
    private JComponent findBar;
    private ThemedEditor editor;
    private LineNumberView lineNumbers;
    // True once the UI is fully built; gates the updateUI() theme-refresh hook so
    // the JTextArea's constructor-time updateUI() call can't touch null fields.
    private boolean uiReady;
    private ActiveLineHighlighter activeLineHighlighter;

    // Every Swing control that must follow Burp's native theme.
    private final List<Component> themedComponents = new ArrayList<>();

    // Zoom + font-family state.
    private int editorFontSize = 13;
    private static final int MIN_FONT = 8;
    private static final int MAX_FONT = 42;
    private String editorFontFamily = Font.MONOSPACED;
    private JComboBox<String> fontCombo;
    private boolean applyingFont;

    // Bottom find/search bar state.
    private JTextField findField;
    private JLabel matchCounter;
    private final List<int[]> searchMatches = new ArrayList<>();
    private int activeMatch = -1;
    private final Highlighter.HighlightPainter matchPainter =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 214, 82, 110));
    private final Highlighter.HighlightPainter activeMatchPainter =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 150, 40, 190));
    private final List<Object> searchHighlightTags = new ArrayList<>();

    // ---------- Single source of truth ----------
    // allNotes is the AUTHORITATIVE, ordered master list of every note. listModel
    // is only a *filtered view* rebuilt from allNotes on demand (audit H1/H3). All
    // mutations go through allNotes; the JList never owns note state.
    private final List<Note> allNotes = new ArrayList<>();
    private Note current;             // the note currently loaded in the editor
    private boolean loadingSelection; // guards programmatic editor/title loads
    private boolean rebuilding;       // guards the selection listener during a view rebuild
    // Debounced autosave. Restarted on each edit; fires once the user pauses,
    // persisting the whole store without thrashing it on every keystroke.
    private Timer autosaveTimer;
    // Guards against a re-entrant theme-refresh cascade: applyThemeToComponent()
    // can re-trigger ThemedEditor.updateUI(), which schedules another refresh.
    // When true, that scheduled refresh is skipped so N rapid theme toggles do
    // not queue O(N) full-tree re-theme passes.
    private boolean themeRefreshing;

    // Montoya registrations captured at install time so extensionUnloaded() can
    // deregister them, preventing a stale suite tab / context-menu provider from
    // lingering after an unload or reload (leak fix).
    private Registration suiteTabRegistration;
    private Registration contextMenuRegistration;


    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        this.store = api.persistence().extensionData();
        api.extension().setName(EXT_NAME);

        SwingUtilities.invokeLater(() -> {
            buildUi();
            loadNotes();
            suiteTabRegistration = api.userInterface().registerSuiteTab(EXT_NAME, root);
            // Realize native theming across every control, then bind the editor.
            uiReady = true; // arm the ThemedEditor.updateUI() theme-refresh hook
            applyNativeThemeToAll();
            syncEditorThemeColors();
            rebindActiveLineHighlighter();
        });

        contextMenuRegistration =
                api.userInterface().registerContextMenuItemsProvider(new BugNotesContextMenu());

        // Full teardown on unload/reload: stop the autosave timer, detach the
        // active-line painter + caret listener, and deregister both Montoya hooks.
        // Without this, repeated load/unload cycles accumulate live Swing Timers,
        // orphaned listeners, and stale provider registrations (memory leak).
        api.extension().registerUnloadingHandler(new ExtensionUnloadingHandler() {
            @Override
            public void extensionUnloaded() {
                SwingUtilities.invokeLater(() -> teardown());
            }
        });

        api.logging().logToOutput(EXT_NAME + " loaded.");
    }

    /** Idempotent teardown invoked on extension unload — releases every resource. */
    private void teardown() {
        try {
            // BLOCKER-1 FIX: the unloading handler is the last guaranteed chance to
            // save. If the user's final keystroke landed inside the debounce window
            // (a pending, not-yet-fired timer), stopping the timer would discard
            // those edits forever. Flush synchronously FIRST, then stop the timer.
            flushPendingEdits();
            if (autosaveTimer != null) {
                autosaveTimer.stop();
                autosaveTimer = null;
            }
            if (editor != null && activeLineHighlighter != null) {
                editor.removeCaretListener(activeLineHighlighter);
                activeLineHighlighter.detach();
            }
            if (suiteTabRegistration != null && suiteTabRegistration.isRegistered()) {
                suiteTabRegistration.deregister();
            }
            if (contextMenuRegistration != null && contextMenuRegistration.isRegistered()) {
                contextMenuRegistration.deregister();
            }
        } catch (Throwable t) {
            if (api != null) api.logging().logToError("BugNotes teardown failed: " + t);
        }
    }

    // ---------- Data model ----------
    private static final class Note {
        final String id;
        String title;
        String createdAt;
        String body;

        Note(String id) { this.id = id; }

        // Drive the sidebar list label natively: any default JList rendering path
        // shows the title instead of a raw object hash (e.g. Note@2e8828f).
        @Override
        public String toString() {
            return title == null || title.isEmpty() ? "(untitled)" : title;
        }
    }

    // ---------- Native theming plumbing ----------
    // All standard Swing controls are registered here and receive Burp's native
    // styling. No hardcoded colors, no custom painting.
    private <T extends Component> T registerThemed(T c) {
        themedComponents.add(c);
        return c;
    }

    /** Apply Burp's native theme to every registered control and the root tree. */
    private void applyNativeThemeToAll() {
        for (Component c : themedComponents) {
            try {
                api.userInterface().applyThemeToComponent(c);
            } catch (Throwable ignored) {
                // Older Burp builds without theming support — safe to skip.
            }
        }
        if (root != null) {
            try { api.userInterface().applyThemeToComponent(root); } catch (Throwable ignored) { }
        }
    }

    // Stock button — no custom painting; Burp themes it natively.
    private static final class ToolButton extends JButton {
        private static final long serialVersionUID = 1L;
        ToolButton(String text) {
            super(text);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
    }

    // The "MD" sidebar badge keeps its rounded chip shape but derives fill and
    // border purely from its themed foreground color (a faint tint of the text
    // color), so it stays high-contrast on any Burp theme with zero hardcoded hex.
    private static final class RoundedBadge extends JLabel {
        private static final long serialVersionUID = 1L;
        RoundedBadge(String text) {
            super(text);
            setOpaque(false);
            setHorizontalAlignment(CENTER);
            setFont(getFont().deriveFont(Font.BOLD, 10f));
            setBorder(new EmptyBorder(4, 10, 4, 10));
            setPreferredSize(new Dimension(44, 22));
        }
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fg = getForeground() == null ? Color.GRAY : getForeground();
            Color fill = new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 34);
            Color line = new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 90);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.setColor(line);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ---------- UI construction ----------
    private void buildUi() {
        root = new JPanel(new BorderLayout());
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildSidebar(), buildCanvas());
        split.setResizeWeight(0.25);
        split.setDividerSize(6);
        split.setBorder(null);
        split.setContinuousLayout(true);
        root.add(split, BorderLayout.CENTER);
    }

    private JComponent buildSidebar() {
        JPanel side = new JPanel(new BorderLayout(0, 8));
        side.setBorder(new EmptyBorder(4, 4, 4, 8));

        JPanel header = new JPanel(new BorderLayout(6, 6));
        JLabel title = new JLabel("BugNotes");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        header.add(title, BorderLayout.WEST);

        ToolButton newBtn = toolBtn("New", "Create a new empty note", e -> createTextNote());
        header.add(newBtn, BorderLayout.EAST);
        side.add(header, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(0, 6));
        searchField = registerThemed(new JTextField());
        searchField.putClientProperty("JTextField.placeholderText", "Filter notes...");
        // M-3: debounce the sidebar filter. Rebuilding the filtered view scans
        // every note body; doing that per keystroke stutters on large corpora.
        // Coalesce a burst of keystrokes into one refilter() after a short pause.
        Timer filterDebounce = new Timer(150, ev -> refilter());
        filterDebounce.setRepeats(false);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filterDebounce.restart(); }
            public void removeUpdate(DocumentEvent e) { filterDebounce.restart(); }
            public void changedUpdate(DocumentEvent e) { filterDebounce.restart(); }
        });
        center.add(searchField, BorderLayout.NORTH);

        listModel = new DefaultListModel<>();
        notesList = new JList<>(listModel);
        notesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        notesList.setCellRenderer(new NoteCellRenderer());
        notesList.setFixedCellHeight(48);
        notesList.addListSelectionListener(e -> {
            // Ignore selection churn while refreshView() is repopulating the model.
            if (!e.getValueIsAdjusting() && !rebuilding) showSelected();
        });
        notesList.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybePopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybePopup(e); }
            private void maybePopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int idx = notesList.locationToIndex(e.getPoint());
                if (idx < 0) return;
                notesList.setSelectedIndex(idx);
                JPopupMenu m = new JPopupMenu();
                JMenuItem del = new JMenuItem("Delete");
                del.addActionListener(a -> deleteSelected());
                m.add(del);
                m.show(notesList, e.getX(), e.getY());
            }
        });
        JScrollPane sp = registerThemed(new JScrollPane(notesList));
        center.add(sp, BorderLayout.CENTER);
        side.add(center, BorderLayout.CENTER);

        JPanel footer = new JPanel(new GridLayout(1, 2, 6, 0));
        footer.add(toolBtn("Delete", "Delete the selected note", e -> deleteSelected()));
        footer.add(toolBtn("Clear All", "Delete every note", e -> clearAll()));
        side.add(footer, BorderLayout.SOUTH);

        side.setPreferredSize(new Dimension(300, 600));
        side.setMinimumSize(new Dimension(220, 200));
        return side;
    }

    private JComponent buildCanvas() {
        JPanel canvas = new JPanel(new BorderLayout(0, 8));
        canvas.setBorder(new EmptyBorder(4, 8, 4, 4));

        JPanel top = new JPanel(new BorderLayout(8, 8));

        JPanel titleRow = new JPanel(new BorderLayout(6, 0));
        JLabel tLbl = new JLabel("Title");
        tLbl.setBorder(new EmptyBorder(0, 0, 0, 8));
        titleField = registerThemed(new JTextField());
        titleField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { onTitleEdited(); }
            public void removeUpdate(DocumentEvent e) { onTitleEdited(); }
            public void changedUpdate(DocumentEvent e) { onTitleEdited(); }
        });
        titleRow.add(tLbl, BorderLayout.WEST);
        titleRow.add(titleField, BorderLayout.CENTER);
        top.add(titleRow, BorderLayout.NORTH);

        // ---------- Unified single-line luxury toolbar ----------
        // BorderLayout hosts the whole strip. Formatters live on the WEST via a
        // leading FlowLayout; vault/canvas utilities are pushed to the far right
        // edge via a TRAILING FlowLayout on the EAST. Both wrappers are
        // transparent, so when the split divider is dragged the row contracts and
        // expands fluidly without clipping buttons.
        JPanel toolbar = new JPanel(new BorderLayout(12, 0));
        toolbar.setBorder(new EmptyBorder(2, 0, 2, 0));

        JPanel formatters = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 6));
        formatters.setOpaque(false);

        // Font-family dropdown at the very start of the toolbar.
        fontCombo = buildFontCombo();
        formatters.add(fontCombo);

        // Zoom controls immediately after the font selector.
        formatters.add(toolBtn("+ Zoom In",  "Increase editor font size", e -> zoom(+1)));
        formatters.add(toolBtn("- Zoom Out", "Decrease editor font size", e -> zoom(-1)));

        formatters.add(toolBtn("B",    "Bold — **text**",         e -> wrapInline("**", "**")));
        formatters.add(toolBtn("I",    "Italic — *text*",         e -> wrapInline("*", "*")));
        formatters.add(toolBtn("H",    "Heading — # ",            e -> prefixLine("# ")));
        // Inline code: the "<>" glyph is naturally wider than single letters, so
        // trim ONLY its inner horizontal padding to match the slim [B]/[I]/[H]
        // profile. Height stays uniform via normalizeRowHeights().
        ToolButton inlineCode = toolBtn("<>", "Inline code — `code`", e -> wrapInline("`", "`"));
        inlineCode.setMargin(new Insets(2, 4, 2, 4));
        formatters.add(inlineCode);
        formatters.add(toolBtn("\u201D", "Blockquote — > ",       e -> prefixLine("> ")));
        formatters.add(toolBtn("Link", "Markdown link — [](URL)", e -> insertLink()));
        formatters.add(toolBtn("Code", "Fenced code block",       e -> insertCodeBlock()));

        JPanel utilities = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 6));
        utilities.setOpaque(false);
        // No Save button by design: edits autosave through a debounced timer
        // (installAutosave()), so notes persist into the .burp project store on
        // their own. Manual saving would be redundant and could mislead the user
        // into thinking edits are lost if they don't click it.
        utilities.add(toolBtn("Copy Raw", "Copy note contents to clipboard",   e -> copyRaw()));

        utilities.add(toolBtn("Clear",    "Empty the editor without deleting", e -> clearCanvas()));
        utilities.add(toolBtn("Import",   "Import a Markdown (.md) file",      e -> importMarkdown()));
        utilities.add(toolBtn("Export",   "Export current note as Markdown",   e -> exportMarkdown()));

        toolbar.add(formatters, BorderLayout.WEST);
        toolbar.add(utilities, BorderLayout.EAST);
        top.add(toolbar, BorderLayout.SOUTH);

        // Keep the existing WEST/EAST FlowLayout framework intact, but force every
        // control on both wrappers to share ONE common height so nothing stretches
        // and there are no vertical gaps — buttons and the combo line up cleanly.
        normalizeRowHeights(formatters);
        normalizeRowHeights(utilities);

        // Hold the whole top strip so it can be shown/hidden with the note state.
        topBar = top;
        canvas.add(top, BorderLayout.NORTH);

        canvasCards = new CardLayout();
        canvasHolder = new JPanel(canvasCards);

        JPanel empty = new JPanel(new GridBagLayout());
        JPanel emptyInner = new JPanel();
        emptyInner.setLayout(new BoxLayout(emptyInner, BoxLayout.Y_AXIS));
        emptyInner.setOpaque(false);
        JLabel a = new JLabel("BugNotes");
        a.setFont(a.getFont().deriveFont(Font.BOLD, 20f));
        a.setForeground(MUTED_FG);
        a.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel b = new JLabel("Select a note on the left,");
        JLabel c = new JLabel("or highlight text in any request or response and choose 'Send to BugNotes'.");
        b.setForeground(MUTED_FG); c.setForeground(MUTED_FG);
        b.setAlignmentX(Component.CENTER_ALIGNMENT);
        c.setAlignmentX(Component.CENTER_ALIGNMENT);
        emptyInner.add(a);
        emptyInner.add(Box.createVerticalStrut(10));
        emptyInner.add(b);
        emptyInner.add(Box.createVerticalStrut(4));
        emptyInner.add(c);
        empty.add(emptyInner);
        canvasHolder.add(empty, "EMPTY");

        // Native Swing editing surface — full caret control for the formatter matrix.
        // Line wrapping is OFF so one document line maps to exactly one screen row,
        // keeping the row-header line numbers and the active-line highlight aligned.
        editor = registerThemed(new ThemedEditor());
        editor.setLineWrap(false);
        editor.setTabSize(4);
        editor.setBorder(new EmptyBorder(10, 12, 10, 12));
        editor.setFont(monoFont());
        installEditorContextMenu();
        installAutosave();


        // Active-line highlighter created here; the CaretListener + painter are
        // (re)bound explicitly in rebindActiveLineHighlighter() after theming so
        // Burp's applyThemeToComponent repaint can never orphan them.
        activeLineHighlighter = new ActiveLineHighlighter(editor);

        JScrollPane editorScroll = registerThemed(new JScrollPane(editor));
        editorScroll.setBorder(null);
        editorScroll.getViewport().setOpaque(true);
        editorScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        // Left-margin dynamic line numbers, anchored as the scroll pane's row header.
        lineNumbers = new LineNumberView(editor);
        editorScroll.setRowHeaderView(lineNumbers);

        canvasHolder.add(editorScroll, "TEXT");
        canvas.add(canvasHolder, BorderLayout.CENTER);

        // Single-row bottom bar: the permanent find bar carries the status label
        // on its far right, collapsing the previous two-line stack into one row
        // so the editor + gutter gain a full line of vertical height.
        findBar = buildFindBar();
        canvas.add(findBar, BorderLayout.SOUTH);

        canvasCards.show(canvasHolder, "EMPTY");
        // Boot state has no note open — hide the editing chrome from the first
        // frame so only the placeholder overlay shows (no flash).
        setEditingChromeVisible(false);
        return canvas;
    }

    /**
     * Tie the top toolbar strip and bottom find bar visibility to note state.
     * We only flip setVisible() (never add/remove components), so BorderLayout
     * treats a hidden child as zero-size and the CENTER card reclaims the space
     * in a single revalidate() pass — no flicker, no layout stutter.
     */
    private void setEditingChromeVisible(boolean visible) {
        boolean changed = false;
        if (topBar != null && topBar.isVisible() != visible) { topBar.setVisible(visible); changed = true; }
        if (findBar != null && findBar.isVisible() != visible) { findBar.setVisible(visible); changed = true; }
        if (changed && root != null) {
            root.revalidate();
            root.repaint();
        }
    }

    // ---------- Permanent bottom find/search bar ----------
    // Always visible (no shortcut trigger). A DocumentListener re-runs the search
    // live as the user types, highlighting every occurrence inside the editor and
    // keeping the "N highlights" counter in sync. Arrow buttons cycle the hits.
    private JComponent buildFindBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(new EmptyBorder(4, 4, 2, 4));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 4));
        left.setOpaque(false);
        JLabel findLbl = new JLabel("Find");
        findLbl.setForeground(MUTED_FG);
        left.add(findLbl);

        findField = registerThemed(new JTextField(24));
        findField.putClientProperty("JTextField.placeholderText", "Search in note...");
        // M-3: debounce the in-note search. runSearch() does a full-document
        // getText().toLowerCase() + re-highlight pass; running it per keystroke
        // stutters on large captured logs. Coalesce keystrokes into one pass.
        Timer searchDebounce = new Timer(150, ev -> runSearch());
        searchDebounce.setRepeats(false);
        findField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { searchDebounce.restart(); }
            public void removeUpdate(DocumentEvent e) { searchDebounce.restart(); }
            public void changedUpdate(DocumentEvent e) { searchDebounce.restart(); }
        });
        findField.addActionListener(e -> navigateMatch(+1));
        left.add(findField);

        left.add(toolBtn("\u2039", "Previous match", e -> navigateMatch(-1)));
        left.add(toolBtn("\u203A", "Next match", e -> navigateMatch(+1)));
        bar.add(left, BorderLayout.WEST);

        // Right side of the SAME row: match counter + dynamic status label, so
        // "Saved." etc. share the find bar's line instead of a second row.
        JPanel right = new JPanel(new FlowLayout(FlowLayout.TRAILING, 12, 4));
        right.setOpaque(false);

        matchCounter = new JLabel("0 highlights");
        matchCounter.setForeground(MUTED_FG);
        right.add(matchCounter);

        statusLabel = new JLabel(" ");
        statusLabel.setForeground(MUTED_FG);
        right.add(statusLabel);

        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // ---------- Button factory ----------
    private ToolButton toolBtn(String label, String tip, java.awt.event.ActionListener a) {
        ToolButton b = new ToolButton(label);
        b.setToolTipText(tip);
        b.addActionListener(a);
        return registerThemed(b);
    }

    /**
     * Give every child of a toolbar FlowLayout row ONE shared height (the tallest
     * child's preferred height) without touching the existing WEST/EAST layout
     * framework. FlowLayout honors each child's preferred size, so pinning a
     * uniform height stops the combo/buttons from rendering at mismatched heights
     * or stretching — they line up as a single clean row of native controls.
     */
    private void normalizeRowHeights(JPanel row) {
        int h = 0;
        for (Component c : row.getComponents()) {
            h = Math.max(h, c.getPreferredSize().height);
        }
        if (h <= 0) return;
        for (Component c : row.getComponents()) {
            Dimension p = c.getPreferredSize();
            Dimension d = new Dimension(p.width, h);
            c.setPreferredSize(d);
            if (c instanceof JComponent) {
                // Cap the max height too so no control stretches vertically.
                ((JComponent) c).setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
            }
        }
    }

    private Font monoFont() {
        return new Font(editorFontFamily, Font.PLAIN, editorFontSize);
    }

    // ---------- Font family dropdown ----------
    // Populated from the system GraphicsEnvironment. Monospaced families float to
    // the top (best for payload/log work); selecting one re-renders the editor
    // and gutter typography instantly via applyEditorFont().
    private JComboBox<String> buildFontCombo() {
        String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        List<String> mono = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (String fam : families) {
            String low = fam.toLowerCase();
            if (low.contains("mono") || low.contains("consol") || low.contains("courier")
                    || low.contains("menlo") || low.contains("code")) {
                mono.add(fam);
            } else {
                other.add(fam);
            }
        }
        List<String> ordered = new ArrayList<>();
        ordered.add(Font.MONOSPACED);
        ordered.addAll(mono);
        ordered.addAll(other);

        JComboBox<String> combo = registerThemed(new JComboBox<>(ordered.toArray(new String[0])));
        combo.setSelectedItem(editorFontFamily);
        combo.setToolTipText("Editor font family");
        // Width only — leave HEIGHT to the shared normalizeRowHeights() pass so the
        // combo matches the native button height instead of forcing its own.
        combo.setPreferredSize(new Dimension(160, combo.getPreferredSize().height));
        combo.setFocusable(false);
        combo.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        combo.addActionListener(e -> {
            if (applyingFont) return;
            Object sel = combo.getSelectedItem();
            if (sel != null) {
                editorFontFamily = sel.toString();
                applyEditorFont();
            }
        });
        return combo;
    }

    // ---------- Zoom ----------
    private void zoom(int direction) {
        int next = editorFontSize + direction;
        if (next < MIN_FONT || next > MAX_FONT) return;
        editorFontSize = next;
        applyEditorFont();
        toast("Font size: " + editorFontSize + " pt.");
    }

    /**
     * Apply the current family + size to the editor and drive the gutter to the
     * identical font in 1:1 sync, then recompute margins so line numbers stay
     * aligned. Any live search highlights are re-laid-out for the new metrics.
     */
    private void applyEditorFont() {
        if (editor == null) return;
        applyingFont = true;
        try {
            Font f = new Font(editorFontFamily, Font.PLAIN, editorFontSize);
            editor.setFont(f);
            if (lineNumbers != null) {
                lineNumbers.setFont(f);   // 1:1 typography sync with the canvas
                lineNumbers.refresh();
            }
            if (fontCombo != null && !editorFontFamily.equals(fontCombo.getSelectedItem())) {
                fontCombo.setSelectedItem(editorFontFamily);
            }
            editor.revalidate();
            editor.repaint();
            runSearch(); // re-highlight with the new metrics so offsets stay valid
        } finally {
            applyingFont = false;
        }
    }

    // ---------- Live in-note search ----------
    private void runSearch() {
        if (editor == null || findField == null) return;
        Highlighter hl = editor.getHighlighter();
        for (Object tag : searchHighlightTags) hl.removeHighlight(tag);
        searchHighlightTags.clear();
        searchMatches.clear();
        activeMatch = -1;

        String query = findField.getText();
        if (query == null || query.isEmpty()) {
            matchCounter.setText("0 highlights");
            editor.repaint();
            return;
        }

        String hay = editor.getText().toLowerCase();
        String needle = query.toLowerCase();
        int from = 0;
        while (true) {
            int idx = hay.indexOf(needle, from);
            if (idx < 0) break;
            searchMatches.add(new int[]{idx, idx + needle.length()});
            from = idx + needle.length();
        }

        // Add one highlight tag per match, keeping searchMatches and
        // searchHighlightTags STRICTLY parallel. If any addHighlight throws, we
        // drop the offending match too so the two lists can never desync (which
        // would later throw IndexOutOfBounds in navigateMatch — R2-6).
        for (int i = 0; i < searchMatches.size(); ) {
            int[] m = searchMatches.get(i);
            try {
                searchHighlightTags.add(hl.addHighlight(m[0], m[1], matchPainter));
                i++;
            } catch (BadLocationException ex) {
                api.logging().logToError("search highlight failed at " + m[0] + ": " + ex);
                searchMatches.remove(i); // keep the two lists in lock-step
            }
        }

        matchCounter.setText(searchMatches.size() + (searchMatches.size() == 1 ? " highlight" : " highlights"));
        if (!searchMatches.isEmpty()) navigateMatch(+1);
        editor.repaint();
    }

    /** Move the active match cursor and scroll it into view, re-tinting it stronger. */
    private void navigateMatch(int direction) {
        if (searchMatches.isEmpty()) return;
        Highlighter hl = editor.getHighlighter();

        if (activeMatch >= 0 && activeMatch < searchHighlightTags.size()) {
            try {
                int[] prev = searchMatches.get(activeMatch);
                hl.removeHighlight(searchHighlightTags.get(activeMatch));
                Object tag = hl.addHighlight(prev[0], prev[1], matchPainter);
                searchHighlightTags.set(activeMatch, tag);
            } catch (BadLocationException ignored) { }
        }

        activeMatch += direction;
        if (activeMatch < 0) activeMatch = searchMatches.size() - 1;
        if (activeMatch >= searchMatches.size()) activeMatch = 0;

        int[] cur = searchMatches.get(activeMatch);
        try {
            hl.removeHighlight(searchHighlightTags.get(activeMatch));
            Object tag = hl.addHighlight(cur[0], cur[1], activeMatchPainter);
            searchHighlightTags.set(activeMatch, tag);
            editor.setCaretPosition(cur[0]);
            Rectangle r = editor.modelToView2D(cur[0]).getBounds();
            editor.scrollRectToVisible(r);
        } catch (BadLocationException ignored) { }

        matchCounter.setText((activeMatch + 1) + " / " + searchMatches.size()
                + (searchMatches.size() == 1 ? " highlight" : " highlights"));
        editor.repaint();
    }

    /**
     * Robust active-line re-binding engine (fixes the vanishing bug).
     *
     * Burp's {@code applyThemeToComponent} can reinstall the editor's UI /
     * highlighter and detach our custom CaretListener + highlight painter. This
     * method fully REMOVES then RE-ADDS both, so the active-line band is
     * guaranteed to survive any theme toggle or forced layout repaint. It is
     * idempotent and safe to call repeatedly.
     */
    private void rebindActiveLineHighlighter() {
        if (editor == null || activeLineHighlighter == null) return;

        // 1. Detach the CaretListener (remove-then-add avoids duplicate firing).
        editor.removeCaretListener(activeLineHighlighter);

        // 2. Drop the orphaned highlight painter tag, if any, from the (possibly
        //    freshly-rebuilt) highlighter.
        activeLineHighlighter.detach();

        // 3. Re-register the painter against the current highlighter instance and
        //    re-add the CaretListener so caret motion repaints the band again.
        activeLineHighlighter.attach();
        editor.addCaretListener(activeLineHighlighter);

        editor.repaint();
    }

    /**
     * Resolve the concrete colors Burp's theme baked into the JTextArea (after
     * applyThemeToComponent) and feed them to our custom-painted gutter and
     * active-line band. Also re-themes all controls and re-binds the highlighter,
     * so a single call fully refreshes the extension for the active theme.
     */
    private void syncEditorThemeColors() {
        if (editor == null) return;
        // Re-entrancy guard (R2-8): applyNativeThemeToAll() below can retrigger
        // ThemedEditor.updateUI(), which schedules another syncEditorThemeColors().
        // Skip the nested call so N rapid theme toggles don't queue O(N) passes.
        if (themeRefreshing) return;
        themeRefreshing = true;
        try {

        // Re-apply native theming first so control colors are current, then read
        // the editor's resolved background to classify Light vs Dark.
        applyNativeThemeToAll();

        Color bg = editor.getBackground();
        Color fg = editor.getForeground();
        if (bg == null) bg = UIManager.getColor("TextArea.background");
        if (bg == null) bg = new Color(43, 43, 43);
        if (fg == null) fg = UIManager.getColor("TextArea.foreground");
        if (fg == null) fg = new Color(220, 220, 220);

        boolean dark = luminance(bg) < 0.5;

        // NEVER write a raw color onto the editor background. applyThemeToComponent
        // (called above) already installs the native, theme-correct background as a
        // UIResource — which Swing is free to overwrite again on the reverse swap.
        // Assigning a plain Color here would mark the background app-owned and
        // freeze it (the Light->Dark stuck-white bug). We only READ the live themed
        // background and derive the gutter shades relative to it, so the gutter
        // tracks Light and Dark automatically with zero hardcoded hex.
        Color gutterBg, gutterFg, gutterLine;
        if (dark) {
            gutterBg = shift(bg, +14);   // slightly lighter than the dark canvas
            gutterFg = blend(fg, bg, 0.45f);
            gutterLine = shift(bg, +32);
        } else {
            gutterBg = shift(bg, -12);   // slightly darker than the light canvas
            gutterFg = blend(fg, bg, 0.40f);
            gutterLine = shift(bg, -40);
        }

        if (lineNumbers != null) {
            lineNumbers.applyColors(gutterBg, gutterFg, gutterLine);
            lineNumbers.setFont(editor.getFont());
            lineNumbers.refresh();
        }
        // 100% native active-line tint: drive it from the editor's own themed
        // selection color (falling back to the L&F selection background). A soft
        // alpha-reduced version reads as a clean band on any Burp theme — no
        // hardcoded light/dark conditional colors.
        if (activeLineHighlighter != null) {
            activeLineHighlighter.setTint(nativeActiveTint());
        }

        // Any theme re-apply may have rebuilt the highlighter — re-bind to be safe.
        rebindActiveLineHighlighter();

        editor.repaint();
        if (root != null) root.repaint();
        } finally {
            themeRefreshing = false;
        }
    }

    private static double luminance(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
    }

    private static Color shift(Color c, int delta) {
        return new Color(clamp(c.getRed() + delta), clamp(c.getGreen() + delta), clamp(c.getBlue() + delta));
    }

    private static Color blend(Color a, Color b, float t) {
        int r = clamp(Math.round(a.getRed() * (1 - t) + b.getRed() * t));
        int g = clamp(Math.round(a.getGreen() * (1 - t) + b.getGreen() * t));
        int bl = clamp(Math.round(a.getBlue() * (1 - t) + b.getBlue() * t));
        return new Color(r, g, bl);
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /**
     * 100% native active-line tint. Pulls the editor's own themed selection
     * color (or the L&F {@code TextArea.selectionBackground}) and softens its
     * alpha so it reads as a clean band on ANY Burp theme — no hardcoded
     * light/dark conditional colors.
     */
    private Color nativeActiveTint() {
        Color sel = null;
        if (editor != null) sel = editor.getSelectionColor();
        if (sel == null) sel = UIManager.getColor("TextArea.selectionBackground");
        if (sel == null) sel = UIManager.getColor("textHighlight");
        if (sel == null) sel = new Color(74, 144, 226); // last-resort neutral blue
        // Reduce alpha so the band tints the row without hiding the glyphs.
        return new Color(sel.getRed(), sel.getGreen(), sel.getBlue(), 48);
    }

    // ---------- Formatter matrix (caret-aware) ----------
    // Every helper operates on the native JTextArea Document and drives the caret
    // to the precise runtime insertion point, then re-focuses the editor so the
    // cursor is immediately visible and blinking where the user will type.

    /**
     * Wrap the current selection with {@code left}/{@code right}. With no
     * selection, injects {@code left+right} and drops the caret dead-center
     * (e.g. {@code **|**}, {@code *|*}, {@code `|`}).
     */
    private void wrapInline(String left, String right) {
        if (current == null) return;
        int selStart = editor.getSelectionStart();
        int selEnd = editor.getSelectionEnd();
        try {
            Document doc = editor.getDocument();
            if (selEnd > selStart) {
                String selected = doc.getText(selStart, selEnd - selStart);
                doc.remove(selStart, selEnd - selStart);
                doc.insertString(selStart, left + selected + right, null);
                editor.setCaretPosition(selStart + left.length() + selected.length() + right.length());
            } else {
                int at = editor.getCaretPosition();
                doc.insertString(at, left + right, null);
                editor.setCaretPosition(at + left.length());
            }
        } catch (BadLocationException ex) {
            api.logging().logToError("wrapInline failed: " + ex);
        }
        syncBodyFromEditor();
        editor.requestFocusInWindow();
    }

    /**
     * Insert {@code prefix} at the start of the caret's current line and place
     * the caret directly after it (e.g. {@code # |}, {@code > |}).
     */
    private void prefixLine(String prefix) {
        if (current == null) return;
        try {
            Document doc = editor.getDocument();
            int caret = editor.getCaretPosition();
            Element rootEl = doc.getDefaultRootElement();
            int lineIdx = rootEl.getElementIndex(caret);
            int lineStart = rootEl.getElement(lineIdx).getStartOffset();
            doc.insertString(lineStart, prefix, null);
            editor.setCaretPosition(lineStart + prefix.length());
        } catch (BadLocationException ex) {
            api.logging().logToError("prefixLine failed: " + ex);
        }
        syncBodyFromEditor();
        editor.requestFocusInWindow();
    }

    /** Insert {@code [](URL)} and drop the caret inside the first brackets: {@code [|](URL)}. */
    private void insertLink() {
        if (current == null) return;
        try {
            Document doc = editor.getDocument();
            int selStart = editor.getSelectionStart();
            int selEnd = editor.getSelectionEnd();
            if (selEnd > selStart) {
                String selected = doc.getText(selStart, selEnd - selStart);
                doc.remove(selStart, selEnd - selStart);
                String snippet = "[" + selected + "](URL)";
                doc.insertString(selStart, snippet, null);
                int urlStart = selStart + 1 + selected.length() + 2; // past "[selected]("
                editor.select(urlStart, urlStart + 3);               // highlight "URL"
            } else {
                int at = editor.getCaretPosition();
                doc.insertString(at, "[](URL)", null);
                editor.setCaretPosition(at + 1); // inside the first brackets: [|](URL)
            }
        } catch (BadLocationException ex) {
            api.logging().logToError("insertLink failed: " + ex);
        }
        syncBodyFromEditor();
        editor.requestFocusInWindow();
    }

    /**
     * Insert a fenced code block with an empty middle line and drop the caret
     * onto that middle line:
     * <pre>
     * ```
     * |
     * ```
     * </pre>
     */
    private void insertCodeBlock() {
        if (current == null) return;
        try {
            Document doc = editor.getDocument();
            int at = editor.getCaretPosition();
            String leading = at > 0 && !doc.getText(at - 1, 1).equals("\n") ? "\n" : "";
            String block = leading + "```\n\n```\n";
            doc.insertString(at, block, null);
            int middle = at + leading.length() + 4; // "```\n".length() == 4
            editor.setCaretPosition(middle);
        } catch (BadLocationException ex) {
            api.logging().logToError("insertCodeBlock failed: " + ex);
        }
        syncBodyFromEditor();
        editor.requestFocusInWindow();
    }

    /** Push the live editor text back into the current note's body model. */
    private void syncBodyFromEditor() {
        if (current == null) return;
        current.body = editor.getText();
    }

    // ---------- Debounced autosave ----------
    // Replaces the manual Save button. Any user edit to the editor document
    // restarts a single-shot Swing timer; when the user pauses for
    // AUTOSAVE_DEBOUNCE_MS the current note's body is flushed to the master list
    // and the whole store is persisted into the .burp project. Programmatic
    // reloads (loadingSelection == true) are ignored so switching notes or a
    // capture-append doesn't trigger a redundant save of unchanged content.
    private void installAutosave() {
        autosaveTimer = new Timer(AUTOSAVE_DEBOUNCE_MS, e -> flushAutosave());
        autosaveTimer.setRepeats(false);

        editor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { scheduleAutosave(); }
            public void removeUpdate(DocumentEvent e) { scheduleAutosave(); }
            public void changedUpdate(DocumentEvent e) { scheduleAutosave(); }
        });
    }

    /** Restart the debounce window on each edit (no-op during programmatic loads). */
    private void scheduleAutosave() {
        if (loadingSelection || current == null) return;
        if (autosaveTimer != null) autosaveTimer.restart();
    }

    /**
     * Flush the live editor + title into the current note and persist the store.
     * The status label is updated silently via logging only (no chirping "Saved."
     * on the shared find-bar row every time the user pauses typing — R2-18).
     */
    private void flushAutosave() {
        if (current == null) return;
        current.body = editor.getText();
        current.title = titleField.getText();
        persistAll();
        notesList.repaint(); // reflect any live title change in the sidebar
        api.logging().logToOutput("[" + EXT_NAME + "] autosaved \"" + current.title + "\"");
    }

    /**
     * Force-persist the live editor + title into the current note IMMEDIATELY,
     * bypassing the debounce window, and cancel any pending timer so it can't
     * fire afterwards against a since-changed {@code current}.
     *
     * This is the single, reusable "commit the outgoing note now" primitive used
     * by:
     *   - teardown() — BLOCKER-1: save edits still inside the debounce window on
     *     unload/reload, which stop() alone would silently drop.
     *   - showSelected() — BLOCKER-2: commit the OUTGOING note before its editor
     *     buffer is overwritten by the incoming note, so a fast note switch inside
     *     the debounce window can never lose the previous note's edits.
     *   - appendCapturedText() — commit any un-flushed editor edits before we
     *     append onto {@code current.body}, so the append builds on the live text
     *     rather than a stale model snapshot.
     *
     * Guarded by {@code loadingSelection}: while a programmatic load is in flight
     * the editor buffer does not represent the user's intent, so we must not write
     * it back over the note model.
     */
    private void flushPendingEdits() {
        if (autosaveTimer != null) autosaveTimer.stop(); // cancel debounce; we save now
        if (current == null || loadingSelection) return;
        current.body = editor.getText();
        current.title = titleField.getText();
        persistAll();
    }


    // ---------- Editor right-click: Send to Decoder ----------
    private void installEditorContextMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem sendDecoder = new JMenuItem("Send to Decoder");
        sendDecoder.addActionListener(e -> {
            String sel = editor.getSelectedText();
            if (sel == null || sel.isEmpty()) {
                toast("Highlight text first, then choose 'Send to Decoder'.");
                return;
            }
            try {
                api.decoder().sendToDecoder(ByteArray.byteArray(sel));
                toast("Sent " + sel.length() + " chars to Decoder.");
            } catch (Throwable t) {
                api.logging().logToError("Send to Decoder failed: " + t);
            }
        });

        JMenuItem cut = new JMenuItem("Cut");
        cut.addActionListener(e -> editor.cut());
        JMenuItem copy = new JMenuItem("Copy");
        copy.addActionListener(e -> editor.copy());
        JMenuItem paste = new JMenuItem("Paste");
        paste.addActionListener(e -> editor.paste());
        JMenuItem selectAll = new JMenuItem("Select All");
        selectAll.addActionListener(e -> editor.selectAll());

        menu.add(sendDecoder);
        menu.addSeparator();
        menu.add(cut);
        menu.add(copy);
        menu.add(paste);
        menu.addSeparator();
        menu.add(selectAll);

        editor.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                boolean hasSel = editor.getSelectedText() != null && !editor.getSelectedText().isEmpty();
                sendDecoder.setEnabled(hasSel);
                cut.setEnabled(hasSel);
                copy.setEnabled(hasSel);
                menu.show(editor, e.getX(), e.getY());
            }
        });
    }

    // ---------- Left-margin line-number gutter (row header view) ----------
    // Custom Graphics2D-painted gutter anchored as the editor scroll pane's row
    // header. Tracks document changes (typing, Enter, large imports) so the
    // number column always matches the live line count; syncs its colors with
    // Burp's active theme via applyColors().
    private static final class LineNumberView extends JComponent implements DocumentListener {
        private static final long serialVersionUID = 1L;
        private final JTextArea text;
        private Color gutterBg = new Color(43, 43, 43);
        private Color gutterFg = new Color(140, 140, 145);
        private Color gutterLine = new Color(70, 70, 70);
        private int lastLineCount = -1;

        LineNumberView(JTextArea text) {
            this.text = text;
            setFont(text.getFont());
            text.getDocument().addDocumentListener(this);
            text.addPropertyChangeListener("font", e -> { setFont(text.getFont()); refresh(); });
        }

        void applyColors(Color bg, Color fg, Color line) {
            this.gutterBg = bg;
            this.gutterFg = fg;
            this.gutterLine = line;
        }

        void refresh() {
            int lines = text.getLineCount();
            if (lines < 1) lines = 1;
            if (lines != lastLineCount) {
                lastLineCount = lines;
                revalidate();
            }
            repaint();
        }

        private int digitsFor(int lines) {
            int d = String.valueOf(Math.max(1, lines)).length();
            return Math.max(2, d);
        }

        @Override
        public Dimension getPreferredSize() {
            int lines = Math.max(1, text.getLineCount());
            FontMetrics fm = getFontMetrics(getFont() == null ? text.getFont() : getFont());
            int charW = fm.charWidth('0');
            int width = charW * digitsFor(lines) + 16;
            int height = Math.max(text.getHeight(), text.getPreferredSize().height);
            return new Dimension(width, height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Rectangle clip = g2.getClipBounds();
            g2.setColor(gutterBg);
            g2.fillRect(clip.x, clip.y, clip.width, clip.height);

            g2.setColor(gutterLine);
            g2.drawLine(getWidth() - 1, clip.y, getWidth() - 1, clip.y + clip.height);

            Font f = getFont() == null ? text.getFont() : getFont();
            g2.setFont(f);
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(gutterFg);

            Document doc = text.getDocument();
            Element rootEl = doc.getDefaultRootElement();

            // Only paint the visible band for performance on very large logs.
            int startOffset = text.viewToModel2D(new Point(0, clip.y));
            int endOffset = text.viewToModel2D(new Point(0, clip.y + clip.height));
            int startLine = rootEl.getElementIndex(Math.max(0, startOffset));
            int endLine = rootEl.getElementIndex(Math.max(0, endOffset));

            int rightPad = 8;
            for (int line = startLine; line <= endLine; line++) {
                Element el = rootEl.getElement(line);
                if (el == null) break;
                try {
                    Rectangle r = text.modelToView2D(el.getStartOffset()).getBounds();
                    String num = String.valueOf(line + 1);
                    int strW = fm.stringWidth(num);
                    int x = getWidth() - strW - rightPad;
                    int baseline = r.y + fm.getAscent() + (r.height - fm.getHeight()) / 2;
                    g2.drawString(num, x, baseline);
                } catch (BadLocationException ignored) {
                    // Line briefly out of view during rapid edits — skip this frame.
                }
            }
            g2.dispose();
        }

        @Override public void insertUpdate(DocumentEvent e) { SwingUtilities.invokeLater(this::refresh); }
        @Override public void removeUpdate(DocumentEvent e) { SwingUtilities.invokeLater(this::refresh); }
        @Override public void changedUpdate(DocumentEvent e) { SwingUtilities.invokeLater(this::refresh); }
    }

    // ---------- Active-line highlighter (VS Code / Sublime effect) ----------
    // A CaretListener + custom HighlightPainter that paints a thin contrasting
    // tint across the full width of the caret's line. attach()/detach() let the
    // owner re-bind it after Burp theme repaints so it never gets orphaned.
    private static final class ActiveLineHighlighter
            implements CaretListener, javax.swing.text.Highlighter.HighlightPainter {

        private final JTextArea text;
        private Color tint = new Color(255, 255, 255, 22);
        private Object highlightTag;

        ActiveLineHighlighter(JTextArea text) {
            this.text = text;
            attach();
        }

        /** Register the painter against the editor's CURRENT highlighter. */
        void attach() {
            try {
                if (highlightTag == null) {
                    highlightTag = text.getHighlighter().addHighlight(0, 0, this);
                }
            } catch (BadLocationException ignored) { }
        }

        /** Drop the painter tag so it can be re-registered cleanly. */
        void detach() {
            if (highlightTag != null) {
                try { text.getHighlighter().removeHighlight(highlightTag); } catch (Throwable ignored) { }
                highlightTag = null;
            }
        }

        void setTint(Color tint) {
            this.tint = tint;
            text.repaint();
        }

        @Override
        public void caretUpdate(CaretEvent e) {
            // Any caret motion (arrows, click, or programmatic markdown inserts)
            // repaints the editor so the active-row band follows instantly.
            text.repaint();
        }

        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, javax.swing.text.JTextComponent c) {
            if (tint == null || tint.getAlpha() == 0) return;
            try {
                int caret = c.getCaretPosition();
                Element rootEl = c.getDocument().getDefaultRootElement();
                int line = rootEl.getElementIndex(caret);
                Element el = rootEl.getElement(line);
                Rectangle r = c.modelToView2D(el.getStartOffset()).getBounds();

                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(tint);
                int width = c.getWidth();
                Component parent = c.getParent();
                if (parent instanceof JViewport) {
                    width = Math.max(width, ((JViewport) parent).getExtentSize().width);
                }
                g2.fillRect(0, r.y, width, r.height);
                g2.dispose();
            } catch (BadLocationException ignored) {
                // Caret momentarily invalid mid-edit — skip drawing this frame.
            }
        }
    }

    // ---------- Themed editor (native theme-change hook) ----------
    // A JTextArea subclass whose updateUI() is the native Swing hook fired on
    // EVERY Look-and-Feel / Burp theme swap. Swing rebuilds the UI delegate here,
    // which installs a fresh Highlighter and can orphan our active-line painter +
    // caret listener. We defer (invokeLater) so the new delegate, highlighter and
    // caret are fully installed, then run the full theme refresh — which re-syncs
    // the gutter/editor colors AND re-binds the highlighter — guaranteeing the
    // band and gutter survive 100+ consecutive theme swaps.
    private final class ThemedEditor extends JTextArea {
        private static final long serialVersionUID = 1L;
        @Override
        public void updateUI() {
            super.updateUI(); // reinstalls the UI delegate + a fresh DefaultHighlighter
            // The very first updateUI() runs from JTextArea's constructor, before
            // our fields (and even the 'editor' reference) exist — gate on uiReady.
            if (!uiReady) return;
            SwingUtilities.invokeLater(() -> {
                if (!uiReady || editor != this) return;
                // syncEditorThemeColors() recomputes gutter/editor colors (fixing
                // the stale dark strip) and calls rebindActiveLineHighlighter(),
                // which remove-before-adds the caret listener and detaches the
                // stale painter tag before attaching a fresh one.
                syncEditorThemeColors();
            });
        }
    }

    // ---------- List rendering ----------
    private static final class NoteCellRenderer extends JPanel implements ListCellRenderer<Note> {
        private static final long serialVersionUID = 1L;
        private final RoundedBadge badge = new RoundedBadge("MD");
        private final JLabel title = new JLabel();
        private final JLabel sub = new JLabel();

        NoteCellRenderer() {
            setLayout(new BorderLayout(10, 0));
            setBorder(new EmptyBorder(6, 10, 6, 10));

            JPanel text = new JPanel(new GridLayout(2, 1));
            text.setOpaque(false);
            title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
            sub.setFont(sub.getFont().deriveFont(Font.PLAIN, 10f));
            sub.setForeground(MUTED_FG);
            text.add(title);
            text.add(sub);

            add(badge, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Note> list, Note n, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            title.setText(n.title == null || n.title.isEmpty() ? "(untitled)" : n.title);
            sub.setText(n.createdAt == null ? "" : n.createdAt);
            // Derive the badge glyph color from the list's themed foreground so it
            // stays legible on any Burp theme.
            Color themedFg = list.getForeground();
            if (themedFg != null) {
                title.setForeground(themedFg);
                badge.setForeground(themedFg);
            }
            if (isSelected) {
                setBackground(list.getSelectionBackground());
                title.setForeground(list.getSelectionForeground());
                badge.setForeground(list.getSelectionForeground());
                setOpaque(true);
            } else {
                setOpaque(false);
            }
            return this;
        }
    }

    // ---------- Note operations ----------
    // Every mutation edits the AUTHORITATIVE allNotes list, persists, then rebuilds
    // the filtered JList view. The view is always derived — never the source.
    private void createTextNote() {
        // H-2: commit the open note's un-flushed editor edits before persistAll()
        // serializes the master list, so creating a new note inside the autosave
        // debounce window cannot write a stale body for the previously open note.
        flushPendingEdits();
        Note n = new Note(nextId());
        n.title = uniqueUntitledTitle();
        n.createdAt = LocalDateTime.now().format(TS_FMT);
        n.body = "";
        allNotes.add(0, n);
        persistAll();
        refreshView();
        selectNote(n);
    }

    private void deleteSelected() {
        Note n = notesList.getSelectedValue();
        if (n == null) return;
        int ok = JOptionPane.showConfirmDialog(root, "Delete \"" + n.title + "\"?", "Delete note",
                JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;
        allNotes.remove(n);
        if (current == n) {
            current = null;
            canvasCards.show(canvasHolder, "EMPTY");
            titleField.setText("");
            editor.setText("");
            setEditingChromeVisible(false);
        }
        persistAll();
        refreshView();
    }

    private void clearAll() {
        if (allNotes.isEmpty()) return;
        int ok = JOptionPane.showConfirmDialog(root, "Delete ALL notes? This cannot be undone.",
                "Clear All", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;
        allNotes.clear();
        current = null;
        canvasCards.show(canvasHolder, "EMPTY");
        titleField.setText("");
        editor.setText("");
        setEditingChromeVisible(false);
        persistAll();
        refreshView();
    }


    private void showSelected() {
        // BLOCKER-2 FIX: commit the OUTGOING note before its editor buffer is
        // overwritten by the incoming one. A fast note switch inside the 800 ms
        // autosave debounce would otherwise let the still-pending timer fire
        // AFTER 'current' had already advanced to the new note, silently
        // discarding the previous note's un-flushed edits. flushPendingEdits()
        // cancels that pending timer and persists the outgoing note now.
        // Guarded internally by loadingSelection so it is a no-op when this call
        // is itself a programmatic (re)load rather than a real user switch.
        flushPendingEdits();

        Note n = notesList.getSelectedValue();
        current = n;
        if (n == null) {
            canvasCards.show(canvasHolder, "EMPTY");
            setEditingChromeVisible(false); // no note -> hide toolbar + find bar
            return;
        }
        loadingSelection = true;
        try {
            titleField.setText(n.title == null ? "" : n.title);
            editor.setText(n.body == null ? "" : n.body);
            editor.setCaretPosition(0);
            canvasCards.show(canvasHolder, "TEXT");
            setEditingChromeVisible(true);  // note open -> reveal editing chrome
        } finally {
            loadingSelection = false;
        }
        // Reset the in-note find state: the previous note's match offsets are
        // meaningless against the newly loaded document, and stale highlight tags
        // would otherwise let navigateMatch() index into the wrong text (R2-12).
        runSearch();
    }

    private void onTitleEdited() {
        if (loadingSelection || current == null) return;
        current.title = titleField.getText();
        notesList.repaint();
    }

    private void clearCanvas() {
        if (current == null) return;
        // Confirmation guard: Clear empties the note body and immediately persists,
        // so a single misclick would otherwise destroy captured payloads with no
        // undo. Require explicit confirmation like Delete / Clear All (R2-19).
        if (editor.getText().isEmpty()) return; // nothing to clear — no-op, no prompt
        int ok = JOptionPane.showConfirmDialog(root,
                "Clear all text in \"" + current.title + "\"? This cannot be undone.",
                "Clear note", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;
        // M-2: cancel any pending autosave timer BEFORE mutating the editor. The
        // setText("") below fires the document listener -> scheduleAutosave(),
        // which would otherwise re-arm the timer and trigger a redundant second
        // persist of the now-empty note ~800 ms later. Persist once, cleanly.
        if (autosaveTimer != null) autosaveTimer.stop();
        editor.setText("");
        current.body = "";
        persistAll();
        editor.requestFocusInWindow();
    }

    private void copyRaw() {
        if (current == null) return;
        String data = editor.getText();
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(data), null);
        toast("Copied " + data.length() + " chars.");
    }

    private void toast(String msg) {
        if (statusLabel != null) statusLabel.setText(msg);
        api.logging().logToOutput("[" + EXT_NAME + "] " + msg);
    }

    /**
     * M-4: produce a "Note N" title that is not already in use. Naming off
     * {@code allNotes.size()+1} collided after deletions (delete "Note 3" of 3,
     * then New -> another "Note 3"); this scans for the lowest free index.
     */
    private String uniqueUntitledTitle() {
        int i = allNotes.size() + 1;
        while (true) {
            String candidate = "Note " + i;
            boolean taken = false;
            for (Note n : allNotes) {
                if (candidate.equals(n.title)) { taken = true; break; }
            }
            if (!taken) return candidate;
            i++;
        }
    }

    // ---------- Persistence ----------
    private String nextId() { return Long.toHexString(ID_SEQ.incrementAndGet()); }

    private void loadNotes() {
        String blob = store.getString(STORE_KEY);
        allNotes.clear();
        if (blob != null && !blob.isEmpty()) {
            try {
                String[] lines = blob.split("\n");
                // Backward compatible: a first line equal to STORE_VERSION is a
                // format tag (BUGNOTES-V1); older/untagged blobs have no tag and
                // every line is a note record. Either way we decode every
                // remaining non-tag line, so existing notes are never dropped.
                int start = (lines.length > 0 && STORE_VERSION.equals(lines[0])) ? 1 : 0;
                for (int i = start; i < lines.length; i++) {
                    String line = lines[i];
                    if (line.isEmpty()) continue;
                    Note n = decodeNote(line);
                    if (n != null) allNotes.add(n);
                }
            } catch (Exception ex) {
                api.logging().logToError("Failed to load notes: " + ex);
            }
        }
        refreshView(); // build the filtered JList view from the authoritative master
    }

    /**
     * Persist the AUTHORITATIVE master list (audit C1/H1). The blob is version
     * tagged on its first line so future loaders can migrate instead of
     * misreading the layout. Never derives its contents from the filtered view.
     */
    private void persistAll() {
        StringBuilder sb = new StringBuilder();
        sb.append(STORE_VERSION).append('\n');
        for (Note n : allNotes) sb.append(encodeNote(n)).append('\n');
        store.setString(STORE_KEY, sb.toString());
    }


    private String encodeNote(Note n) {
        return n.id + '|' + b64(n.title) + '|' + b64(n.createdAt) + '|' + b64(n.body);
    }

    private Note decodeNote(String line) {
        String[] p = line.split("\\|", -1);
        if (p.length < 4) return null;
        Note n = new Note(p[0]);
        n.title = ub64(p[1]);
        n.createdAt = ub64(p[2]);
        n.body = ub64(p[3]);
        return n;
    }

    // UTF-8 on BOTH sides so raw payloads with non-ASCII bytes round-trip
    // losslessly regardless of the host OS default charset (audit: payload
    // integrity). Never rely on the platform default here.
    private static String b64(String s) {
        return s == null ? "" : Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
    private static String ub64(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }


    // ---------- Sidebar filter (pure derived view) ----------
    // refilter() NEVER writes back into allNotes — it only reads the master and
    // projects the matching subset into listModel. This is the fix for the
    // silent data-loss bug where filtering then clearing the query used to
    // rebuild the master from the shrunken visible view and drop unmatched notes.
    private void refilter() {
        refreshView();
    }

    /**
     * Rebuild the JList view from the authoritative {@link #allNotes} master,
     * applying the current sidebar filter query. The {@code rebuilding} guard
     * suppresses the selection listener so re-adding elements doesn't fire a
     * spurious showSelected() that would clobber the editor mid-rebuild.
     */
    private void refreshView() {
        if (listModel == null) return;
        String q = searchField == null ? "" : searchField.getText().toLowerCase().trim();
        Note keepSel = current;
        rebuilding = true;
        try {
            listModel.clear();
            for (Note n : allNotes) {
                if (q.isEmpty() || matches(n, q)) listModel.addElement(n);
            }
        } finally {
            rebuilding = false;
        }
        // Restore the prior selection if it survived the filter, without firing
        // a redundant reload of an already-open note.
        if (keepSel != null && listModel.contains(keepSel)) {
            notesList.setSelectedValue(keepSel, true);
        }
    }

    /** Select a note in the view (used right after a create/duplicate). */
    private void selectNote(Note n) {
        if (n == null) return;
        if (!listModel.contains(n)) refreshView();
        if (listModel.contains(n)) notesList.setSelectedValue(n, true);
    }


    private boolean matches(Note n, String q) {
        return (n.title != null && n.title.toLowerCase().contains(q))
                || (n.body != null && n.body.toLowerCase().contains(q));
    }

    // ---------- Context menu (Send selection to BugNotes) ----------
    private final class BugNotesContextMenu implements ContextMenuItemsProvider {
        @Override
        public List<Component> provideMenuItems(ContextMenuEvent event) {
            List<Component> items = new ArrayList<>();
            JMenuItem send = new JMenuItem("Send selection to BugNotes");
            send.addActionListener(e -> captureFromEvent(event));
            items.add(send);
            return items;
        }
    }

    private void captureFromEvent(ContextMenuEvent event) {
        // M-1: the menu action fires on the EDT, and reading a large HTTP response
        // (toByteArray + subArray + new String) is a full-buffer copy that would
        // hitch the UI on big messages. Do the byte extraction OFF the EDT in a
        // SwingWorker; hop back to the EDT only to append/notify.
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    Optional<MessageEditorHttpRequestResponse> me = event.messageEditorRequestResponse();
                    if (me.isPresent()) {
                        MessageEditorHttpRequestResponse mer = me.get();
                        HttpRequestResponse rr = mer.requestResponse();
                        Optional<Range> range = mer.selectionOffsets();
                        if (range.isPresent() && rr != null) {
                            boolean fromResponse = mer.selectionContext()
                                    == MessageEditorHttpRequestResponse.SelectionContext.RESPONSE;
                            ByteArray src = fromResponse && rr.response() != null
                                    ? rr.response().toByteArray()
                                    : rr.request().toByteArray();
                            Range r = range.get();
                            int start = Math.max(0, r.startIndexInclusive());
                            int end = Math.min(src.length(), r.endIndexExclusive());
                            // UTF-8 decode so captured payloads with non-ASCII bytes
                            // are not corrupted by the host OS default charset (R2-1).
                            if (end > start) {
                                return new String(src.subArray(start, end).getBytes(),
                                        StandardCharsets.UTF_8);
                            }
                        }
                    }
                } catch (Throwable t) {
                    api.logging().logToError("Selection capture failed: " + t);
                }
                return null;
            }
            @Override
            protected void done() {
                String captured;
                try {
                    captured = get();
                } catch (Exception ex) {
                    api.logging().logToError("Selection capture failed: " + ex);
                    captured = null;
                }
                if (captured == null || captured.isEmpty()) {
                    JOptionPane.showMessageDialog(root,
                            "Highlight the text you want to capture, then choose 'Send selection to BugNotes'.",
                            "BugNotes", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                appendCapturedText(captured);
            }
        }.execute();
    }

    /** Append raw selection to the current note (or first note, or a bootstrap note),
     *  separated by exactly three linebreaks from the prior content. No metadata. */
    private void appendCapturedText(String captured) {
        // BLOCKER-2 (append variant): if the capture target IS the open note and
        // the user has un-flushed edits sitting inside the debounce window, commit
        // them first so the append builds on the LIVE editor text — not a stale
        // note.body snapshot that would clobber those edits. Only commit when the
        // open note is the target; a background target keeps its own persisted body.
        if (current != null) flushPendingEdits();

        Note target = current;
        if (target == null && !allNotes.isEmpty()) target = allNotes.get(0);
        boolean created = false;
        if (target == null) {
            target = new Note(nextId());
            target.title = "Scratch Notes";
            target.createdAt = LocalDateTime.now().format(TS_FMT);
            target.body = "";
            allNotes.add(0, target);
            created = true;
        }

        String existing = target.body == null ? "" : target.body;
        // STRICT 3-line spacing between prior content and the fresh append.
        target.body = existing.isEmpty() ? captured : existing + "\n\n\n" + captured;

        if (created) refreshView();

        if (target == current) {
            loadingSelection = true;
            try {
                editor.setText(target.body);
                editor.setCaretPosition(editor.getDocument().getLength());
            } finally {
                loadingSelection = false;
            }
        } else {
            selectNote(target);
        }
        persistAll();
        toast("Appended " + captured.length() + " chars to \"" + target.title + "\".");
    }


    // ---------- Import / Export ----------
    private void exportMarkdown() {
        if (current == null) { toast("Select a note to export."); return; }
        final String content = editor.getText();
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Export note as Markdown");
        fc.setFileFilter(new FileNameExtensionFilter("Markdown (*.md)", "md"));
        String base = current.title == null || current.title.isEmpty() ? "note" : current.title;
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!base.toLowerCase().endsWith(".md")) base = base + ".md";
        fc.setSelectedFile(new File(base));
        if (fc.showSaveDialog(root) != JFileChooser.APPROVE_OPTION) return;
        File chosen = fc.getSelectedFile();
        if (!chosen.getName().toLowerCase().endsWith(".md")) {
            chosen = new File(chosen.getParentFile(), chosen.getName() + ".md");
        }
        final File out = chosen;
        // H-3: write the file OFF the EDT so a large note or a slow/network path
        // cannot freeze the whole Burp UI. Disk I/O runs in doInBackground();
        // the toast/error dialog fires back on the EDT in done().
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                Files.writeString(out.toPath(), content, StandardCharsets.UTF_8);
                return null;
            }
            @Override
            protected void done() {
                try {
                    get(); // surface any I/O exception thrown in the background
                    toast("Exported to " + out.getName());
                } catch (Exception ex) {
                    api.logging().logToError("Export failed: " + ex);
                    JOptionPane.showMessageDialog(root, "Export failed: " + ex.getMessage(),
                            "BugNotes", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void importMarkdown() {
        // H-2: commit the open note's un-flushed editor edits before we mutate the
        // master list and persist, so importing inside the autosave debounce
        // window cannot write a stale body for the previously open note.
        flushPendingEdits();
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Import a Markdown file");
        fc.setFileFilter(new FileNameExtensionFilter("Markdown (*.md, *.markdown, *.txt)",
                "md", "markdown", "txt"));
        if (fc.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return;
        final Path p = fc.getSelectedFile().toPath();
        // H-3: read the file OFF the EDT. A large or slow/network-mounted file
        // would otherwise freeze the whole Burp UI for the duration of the read.
        // The disk work happens in doInBackground(); the note is built and the
        // store persisted back on the EDT in done().
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return Files.readString(p);
            }
            @Override
            protected void done() {
                try {
                    String body = get();
                    Note n = new Note(nextId());
                    n.createdAt = LocalDateTime.now().format(TS_FMT);
                    String name = p.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    n.title = dot > 0 ? name.substring(0, dot) : name;
                    n.body = body;
                    allNotes.add(0, n);
                    persistAll();
                    refreshView();
                    selectNote(n);
                    toast("Imported " + body.length() + " chars from " + name);
                } catch (Exception ex) {
                    api.logging().logToError("Import failed: " + ex);
                    JOptionPane.showMessageDialog(root, "Import failed: " + ex.getMessage(),
                            "BugNotes", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
