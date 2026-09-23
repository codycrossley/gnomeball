package gay.runescape.gnomeball;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.*;

public class GnomeballPanel extends PluginPanel
{
    private static final int BORDER = 8;
    private static final Color COLOR_REFEREE = new Color(60, 179, 74);
    private static final Color COLOR_BALL    = new Color(255, 200, 60, 255);
    private static final Color ROW_EVEN      = new Color(40, 40, 40);
    private static final Color ROW_ODD       = new Color(50, 50, 50);

    private final GnomeballPlugin plugin;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardPanel = new JPanel(cardLayout);

    // Connect card
    private final JTextField joinCodeField = new JTextField();
    private final JButton joinBtn   = new JButton("Join Game");
    private final JButton createBtn = new JButton("Create New Game");

    // In-game card
    private final JLabel joinCodeValueLabel = new JLabel("—");
    private final JPanel scoreboardPanel    = new JPanel();
    private final JTextField teamANameField = new JTextField("Team A");
    private final JTextField teamBNameField = new JTextField("Team B");
    // Referee-clickable color swatches -- see wireColorSwatch(). A plain JPanel rather than a
    // JButton so the team color itself (its background) IS the whole control, no separate icon
    // or label needed.
    private final JPanel teamAColorSwatch = new JPanel();
    private final JPanel teamBColorSwatch = new JPanel();
    private final JLabel scoreALabel       = new JLabel("0");
    private final JLabel scoreBLabel       = new JLabel("0");
    private final JButton scoreAMinus = new JButton("-");
    private final JButton scoreAPlus  = new JButton("+");
    private final JButton scoreBMinus = new JButton("-");
    private final JButton scoreBPlus  = new JButton("+");
    private final JPanel hostScoreAPanel = new JPanel();
    private final JPanel hostScoreBPanel = new JPanel();
    private final JPanel rosterTablePanel   = new JPanel();

    // Host controls (grouping card — visible to host only, LOBBY or ACTIVE)
    private final JPanel hostControlsCard = new JPanel();

    // Field Presets — unified placement workflow (host; shown whenever the host card is shown).
    // Covers both a host-dimensioned "Custom Grid" and named presets/saved slots through one
    // dropdown + Place/Remove/Save, rather than a separate grid-specific tool.
    private final JPanel presetPanel = new JPanel();
    private final JComboBox<String> presetDropdown = new JComboBox<>();
    private final JPanel gridSizeRow = new JPanel();
    private final JSpinner gridWidthSpinner  = new JSpinner(new SpinnerNumberModel(5, 1, 50, 1));
    private final JSpinner gridHeightSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 50, 1));
    private final JButton placePresetBtn = new JButton("Place");
    private final JButton removePresetBtn = new JButton("Remove");
    private final JButton saveFieldBtn = new JButton("Save");

    private final JButton clearArenaBtn = new JButton("Clear Current Arena");
    private final JButton removeFlagsBtn = new JButton("Remove Flags");

    // Host pre-start (LOBBY only, within host card)
    private final JPanel hostPreStartPanel = new JPanel();
    private final JSpinner durationSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 120, 1));
    private final JButton startGameBtn     = new JButton("Start Game");

    // Host in-game controls (ACTIVE only, within host card)
    private final JPanel hostInGamePanel = new JPanel();
    private final JButton endGameBtn   = new JButton("End Game");

    // Referee controls (referee role). Broadcast Message is available in LOBBY or ACTIVE; the
    // whistle/clock controls only make sense once there's an actual running clock, so they're
    // confined to a nested ACTIVE-only sub-panel.
    private final JPanel  refereePanel    = new JPanel();
    private final JPanel  refereeMessagePanel = new JPanel();
    private final JTextField refereeMessageField = new JTextField();
    private final JButton sendMessageBtn = new JButton("Send Message");
    private final JPanel  refereeActiveControlsPanel = new JPanel();
    private final JButton whistleBtn      = new JButton("Blow Whistle");
    private final JButton timerToggleBtn  = new JButton("STOP");
    private final JButton setClockBtn     = new JButton("Set Clock");

    // All players
    private final JButton leaveGameBtn = new JButton("Leave Game");

    private String lastRosterKey = null;

    public GnomeballPanel(GnomeballPlugin plugin)
    {
        // wrap=true: let PluginPanel wrap this panel in its own vertical JScrollPane, so the
        // whole panel scrolls once content (e.g. a large roster) exceeds the visible sidebar height.
        super(true);
        setBorder(BorderFactory.createEmptyBorder());
        this.plugin = plugin;
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildCardPanel(), BorderLayout.CENTER);
    }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    private JPanel buildTopPanel()
    {
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(new EmptyBorder(BORDER, BORDER, BORDER, BORDER));
        top.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel title = new JLabel("Gnomeball");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(ColorScheme.BRAND_ORANGE);
        header.add(title, BorderLayout.WEST);

        top.add(header);
        return top;
    }

    private JPanel buildCardPanel()
    {
        cardPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        cardPanel.add(buildConnectCard(), "CONNECT");
        cardPanel.add(buildInGameCard(), "IN_GAME");

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrapper.add(cardPanel, BorderLayout.NORTH);
        return wrapper;
    }

    private JPanel buildConnectCard()
    {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(BORDER, BORDER, BORDER, BORDER));
        card.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel joinLabel = new JLabel("Join Code");
        joinLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        joinLabel.setAlignmentX(LEFT_ALIGNMENT);

        joinCodeField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        joinCodeField.setAlignmentX(LEFT_ALIGNMENT);

        joinBtn.setAlignmentX(LEFT_ALIGNMENT);
        joinBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        joinBtn.addActionListener(e -> plugin.onJoinClicked(joinCodeField.getText().trim()));

        JSeparator sep = new JSeparator();
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(LEFT_ALIGNMENT);

        createBtn.setAlignmentX(LEFT_ALIGNMENT);
        createBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        createBtn.addActionListener(e -> plugin.onCreateClicked());

        card.add(joinLabel);
        card.add(Box.createVerticalStrut(4));
        card.add(joinCodeField);
        card.add(Box.createVerticalStrut(4));
        card.add(joinBtn);
        card.add(Box.createVerticalStrut(10));
        card.add(sep);
        card.add(Box.createVerticalStrut(10));
        card.add(createBtn);
        return card;
    }

    private JPanel buildInGameCard()
    {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(BORDER, BORDER, BORDER, BORDER));
        card.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Join code row
        JPanel codeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        codeRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
        codeRow.setAlignmentX(LEFT_ALIGNMENT);
        JLabel codeLabel = new JLabel("Code:");
        codeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        codeRow.add(codeLabel);
        joinCodeValueLabel.setForeground(Color.WHITE);
        codeRow.add(joinCodeValueLabel);
        JButton copyBtn = new JButton("Copy");
        copyBtn.setMargin(new Insets(2, 6, 2, 6));
        copyBtn.addActionListener(e -> copyToClipboard(plugin.getJoinCode()));
        codeRow.add(copyBtn);
        card.add(codeRow);
        card.add(Box.createVerticalStrut(8));

        // Scoreboard
        JLabel scoreboardTitle = new JLabel("SCOREBOARD");
        scoreboardTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        scoreboardTitle.setFont(FontManager.getRunescapeSmallFont());
        scoreboardTitle.setAlignmentX(LEFT_ALIGNMENT);
        card.add(scoreboardTitle);
        card.add(Box.createVerticalStrut(4));

        scoreboardPanel.setLayout(new BoxLayout(scoreboardPanel, BoxLayout.Y_AXIS));
        scoreboardPanel.setBackground(new Color(30, 30, 30));
        scoreboardPanel.setBorder(new EmptyBorder(6, 8, 6, 8));
        scoreboardPanel.setAlignmentX(LEFT_ALIGNMENT);

        // Team A name (+ referee-clickable color swatch)
        teamANameField.setHorizontalAlignment(SwingConstants.CENTER);
        teamANameField.setFont(FontManager.getRunescapeBoldFont());
        teamANameField.setBackground(new Color(30, 30, 30));
        teamANameField.setBorder(BorderFactory.createEmptyBorder());
        teamANameField.setEditable(false);
        teamANameField.setAlignmentX(CENTER_ALIGNMENT);
        teamANameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        teamANameField.addActionListener(e -> commitTeamName("TEAM_A", teamANameField.getText().trim()));
        teamANameField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { commitTeamName("TEAM_A", teamANameField.getText().trim()); }
        });
        wireColorSwatch(teamAColorSwatch, "TEAM_A");
        JPanel teamARow = new JPanel(new BorderLayout(4, 0));
        teamARow.setBackground(new Color(30, 30, 30));
        teamARow.setAlignmentX(CENTER_ALIGNMENT);
        teamARow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        teamARow.add(teamAColorSwatch, BorderLayout.WEST);
        teamARow.add(teamANameField, BorderLayout.CENTER);
        scoreboardPanel.add(teamARow);

        // Team A score row
        JPanel scoreARow = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 2));
        scoreARow.setBackground(new Color(30, 30, 30));
        scoreARow.setAlignmentX(CENTER_ALIGNMENT);
        scoreARow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        scoreALabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
        Insets btnInsets = new Insets(1, 6, 1, 6);
        scoreAMinus.setMargin(btnInsets);
        scoreAPlus.setMargin(btnInsets);
        scoreAMinus.addActionListener(e -> plugin.onUpdateScore("TEAM_A", Math.max(0, plugin.getTeamAScore() - 1)));
        scoreAPlus.addActionListener(e -> plugin.onUpdateScore("TEAM_A", plugin.getTeamAScore() + 1));
        hostScoreAPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 2, 0));
        hostScoreAPanel.setBackground(new Color(30, 30, 30));
        hostScoreAPanel.setVisible(false);
        hostScoreAPanel.add(scoreAMinus);
        hostScoreAPanel.add(scoreAPlus);
        scoreARow.add(scoreALabel);
        scoreARow.add(hostScoreAPanel);
        scoreboardPanel.add(scoreARow);

        // Separator
        JLabel vsLabel = new JLabel("vs");
        vsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        vsLabel.setFont(FontManager.getRunescapeSmallFont());
        vsLabel.setHorizontalAlignment(SwingConstants.CENTER);
        vsLabel.setAlignmentX(CENTER_ALIGNMENT);
        scoreboardPanel.add(vsLabel);

        // Team B score row
        JPanel scoreBRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 2));
        scoreBRow.setBackground(new Color(30, 30, 30));
        scoreBRow.setAlignmentX(CENTER_ALIGNMENT);
        scoreBRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        scoreBLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
        scoreBMinus.setMargin(btnInsets);
        scoreBPlus.setMargin(btnInsets);
        scoreBMinus.addActionListener(e -> plugin.onUpdateScore("TEAM_B", Math.max(0, plugin.getTeamBScore() - 1)));
        scoreBPlus.addActionListener(e -> plugin.onUpdateScore("TEAM_B", plugin.getTeamBScore() + 1));
        hostScoreBPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 2, 0));
        hostScoreBPanel.setBackground(new Color(30, 30, 30));
        hostScoreBPanel.setVisible(false);
        hostScoreBPanel.add(scoreBMinus);
        hostScoreBPanel.add(scoreBPlus);
        scoreBRow.add(scoreBLabel);
        scoreBRow.add(hostScoreBPanel);
        scoreboardPanel.add(scoreBRow);

        // Team B name (+ referee-clickable color swatch)
        teamBNameField.setHorizontalAlignment(SwingConstants.CENTER);
        teamBNameField.setFont(FontManager.getRunescapeBoldFont());
        teamBNameField.setBackground(new Color(30, 30, 30));
        teamBNameField.setBorder(BorderFactory.createEmptyBorder());
        teamBNameField.setEditable(false);
        teamBNameField.setAlignmentX(CENTER_ALIGNMENT);
        teamBNameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        teamBNameField.addActionListener(e -> commitTeamName("TEAM_B", teamBNameField.getText().trim()));
        teamBNameField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { commitTeamName("TEAM_B", teamBNameField.getText().trim()); }
        });
        wireColorSwatch(teamBColorSwatch, "TEAM_B");
        JPanel teamBRow = new JPanel(new BorderLayout(4, 0));
        teamBRow.setBackground(new Color(30, 30, 30));
        teamBRow.setAlignmentX(CENTER_ALIGNMENT);
        teamBRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        teamBRow.add(teamBColorSwatch, BorderLayout.WEST);
        teamBRow.add(teamBNameField, BorderLayout.CENTER);
        scoreboardPanel.add(teamBRow);
        card.add(scoreboardPanel);
        card.add(Box.createVerticalStrut(12));

        // Roster
        JLabel rosterTitle = new JLabel("ROSTER");
        rosterTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        rosterTitle.setFont(FontManager.getRunescapeSmallFont());
        rosterTitle.setAlignmentX(LEFT_ALIGNMENT);
        card.add(rosterTitle);
        card.add(Box.createVerticalStrut(4));

        // Roster table — no internal scroll region; the whole panel scrolls (see constructor),
        // so a growing roster just pushes the panel's overall scroll extent, not its own nested one.
        rosterTablePanel.setLayout(new BoxLayout(rosterTablePanel, BoxLayout.Y_AXIS));
        rosterTablePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        rosterTablePanel.setAlignmentX(LEFT_ALIGNMENT);
        card.add(rosterTablePanel);
        card.add(Box.createVerticalStrut(12));

        // ===== HOST CONTROLS card (host only; groups Field, Setup, In-Game) =====
        hostControlsCard.setLayout(new BoxLayout(hostControlsCard, BoxLayout.Y_AXIS));
        hostControlsCard.setBackground(new Color(34, 30, 26));
        hostControlsCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(90, 70, 40), 1),
            new EmptyBorder(8, 8, 8, 8)));
        hostControlsCard.setAlignmentX(LEFT_ALIGNMENT);
        hostControlsCard.setVisible(false);

        JLabel hostCardTitle = new JLabel("HOST CONTROLS");
        hostCardTitle.setForeground(ColorScheme.BRAND_ORANGE);
        hostCardTitle.setFont(FontManager.getRunescapeSmallFont());
        hostCardTitle.setAlignmentX(LEFT_ALIGNMENT);
        hostControlsCard.add(hostCardTitle);
        hostControlsCard.add(Box.createVerticalStrut(6));

        // Field Presets — one unified workflow for laying out the field, shown whenever the host
        // card is shown. "Custom Grid" (host-dimensioned, via the spinners below) sits in the same
        // dropdown as named presets/saved slots — picking any of them and hitting Place/Remove
        // drives the exact same click-to-place pipeline, so there's only ever one Place button.
        JLabel presetTitle = new JLabel("Field Presets");
        presetTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        presetTitle.setFont(FontManager.getRunescapeSmallFont());
        presetTitle.setAlignmentX(LEFT_ALIGNMENT);

        presetPanel.setLayout(new BoxLayout(presetPanel, BoxLayout.Y_AXIS));
        presetPanel.setBackground(new Color(34, 30, 26));
        presetPanel.setAlignmentX(LEFT_ALIGNMENT);

        presetDropdown.setAlignmentX(LEFT_ALIGNMENT);
        presetDropdown.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        presetDropdown.addActionListener(e -> refreshGridButton());
        refreshPresetDropdownItems();
        presetPanel.add(presetDropdown);
        presetPanel.add(Box.createVerticalStrut(4));

        // Only relevant (and only shown) while "Custom Grid" is selected
        gridSizeRow.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));
        gridSizeRow.setBackground(new Color(34, 30, 26));
        gridSizeRow.setAlignmentX(LEFT_ALIGNMENT);
        JLabel wLabel = new JLabel("W:");
        wLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        gridSizeRow.add(wLabel);
        gridWidthSpinner.setPreferredSize(new Dimension(50, 24));
        gridWidthSpinner.addChangeListener(e -> refreshGridButton());
        gridSizeRow.add(gridWidthSpinner);
        gridSizeRow.add(Box.createHorizontalStrut(6));
        JLabel hLabel = new JLabel("H:");
        hLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        gridSizeRow.add(hLabel);
        gridHeightSpinner.setPreferredSize(new Dimension(50, 24));
        gridHeightSpinner.addChangeListener(e -> refreshGridButton());
        gridSizeRow.add(gridHeightSpinner);
        presetPanel.add(gridSizeRow);
        presetPanel.add(Box.createVerticalStrut(4));

        JPanel presetBtnRow = new JPanel(new GridLayout(1, 2, 4, 0));
        presetBtnRow.setBackground(new Color(34, 30, 26));
        presetBtnRow.setAlignmentX(LEFT_ALIGNMENT);
        presetBtnRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        placePresetBtn.addActionListener(e ->
        {
            if (plugin.isPresetPlacementMode())
            {
                plugin.cancelPresetMode();
            }
            else
            {
                FieldPreset resolved = resolveSelectedPreset();
                if (resolved == null || resolved.isEmpty()) return;
                plugin.startPresetPlacement(resolved);
            }
            refreshGridButton();
        });
        presetBtnRow.add(placePresetBtn);

        removePresetBtn.setForeground(new Color(220, 60, 60));
        removePresetBtn.addActionListener(e ->
        {
            if (plugin.isPresetRemovalMode())
            {
                plugin.cancelPresetMode();
            }
            else
            {
                FieldPreset resolved = resolveSelectedPreset();
                if (resolved == null || resolved.isEmpty()) return;
                plugin.startPresetRemoval(resolved);
            }
            refreshGridButton();
        });
        presetBtnRow.add(removePresetBtn);

        presetPanel.add(presetBtnRow);
        presetPanel.add(Box.createVerticalStrut(4));

        saveFieldBtn.setAlignmentX(LEFT_ALIGNMENT);
        saveFieldBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        saveFieldBtn.addActionListener(e ->
        {
            int slotIndex = resolveSelectedCustomSlotIndex();
            if (slotIndex < 0) return;
            if (plugin.getCustomSlot(slotIndex) != null)
            {
                int choice = JOptionPane.showConfirmDialog(this,
                    "Overwrite Custom Slot " + (slotIndex + 1) + " with the current field?",
                    "Overwrite Saved Field", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) return;
            }
            plugin.saveCurrentFieldToCustomSlot(slotIndex);
            refreshPresetDropdownItems();
            refreshGridButton();
        });
        presetPanel.add(saveFieldBtn);

        hostControlsCard.add(presetTitle);
        hostControlsCard.add(Box.createVerticalStrut(4));
        hostControlsCard.add(presetPanel);
        hostControlsCard.add(Box.createVerticalStrut(8));

        // Clears everything from both Field Presets and Zones — applies to the whole arena, so
        // it lives below both rather than inside either sub-tool.
        clearArenaBtn.setForeground(new Color(220, 60, 60));
        clearArenaBtn.setAlignmentX(LEFT_ALIGNMENT);
        clearArenaBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        clearArenaBtn.addActionListener(e ->
        {
            int choice = JOptionPane.showConfirmDialog(this,
                "Remove all field and zone tiles for this game?",
                "Clear Current Arena", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
            plugin.onClearArenaClicked();
        });
        hostControlsCard.add(clearArenaBtn);
        hostControlsCard.add(Box.createVerticalStrut(4));

        // Clears every referee-placed flag, leaving the field itself untouched.
        removeFlagsBtn.setAlignmentX(LEFT_ALIGNMENT);
        removeFlagsBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        removeFlagsBtn.addActionListener(e -> plugin.onRemoveFlagsClicked());
        hostControlsCard.add(removeFlagsBtn);
        hostControlsCard.add(Box.createVerticalStrut(8));

        // Setup sub-group (LOBBY only): duration + start
        hostPreStartPanel.setLayout(new BoxLayout(hostPreStartPanel, BoxLayout.Y_AXIS));
        hostPreStartPanel.setBackground(new Color(34, 30, 26));
        hostPreStartPanel.setAlignmentX(LEFT_ALIGNMENT);
        hostPreStartPanel.setVisible(false);

        JPanel durationRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        durationRow.setBackground(new Color(34, 30, 26));
        durationRow.setAlignmentX(LEFT_ALIGNMENT);
        JLabel durLabel = new JLabel("Duration (min): ");
        durLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        durationRow.add(durLabel);
        durationSpinner.setPreferredSize(new Dimension(60, 24));
        durationRow.add(durationSpinner);
        hostPreStartPanel.add(durationRow);
        hostPreStartPanel.add(Box.createVerticalStrut(6));

        startGameBtn.setAlignmentX(LEFT_ALIGNMENT);
        startGameBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        startGameBtn.addActionListener(e -> plugin.onStartClicked((Integer) durationSpinner.getValue() * 60));
        hostPreStartPanel.add(startGameBtn);
        hostControlsCard.add(hostPreStartPanel);

        // In-game sub-group (ACTIVE only): end game
        hostInGamePanel.setLayout(new BoxLayout(hostInGamePanel, BoxLayout.Y_AXIS));
        hostInGamePanel.setBackground(new Color(34, 30, 26));
        hostInGamePanel.setAlignmentX(LEFT_ALIGNMENT);
        hostInGamePanel.setVisible(false);

        endGameBtn.setAlignmentX(LEFT_ALIGNMENT);
        endGameBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        endGameBtn.setForeground(new Color(220, 60, 60));
        endGameBtn.addActionListener(e -> plugin.onEndClicked());
        hostInGamePanel.add(endGameBtn);
        hostControlsCard.add(hostInGamePanel);

        card.add(hostControlsCard);
        card.add(Box.createVerticalStrut(8));

        // ===== REFEREE CONTROLS card (referee role only). Broadcast Message is shown in LOBBY
        // or ACTIVE; whistle/clock controls are confined to the nested ACTIVE-only sub-panel
        // below since they only make sense once there's an actual running clock. =====
        refereePanel.setLayout(new BoxLayout(refereePanel, BoxLayout.Y_AXIS));
        refereePanel.setBackground(new Color(24, 34, 26));
        refereePanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(50, 90, 55), 1),
            new EmptyBorder(8, 8, 8, 8)));
        refereePanel.setAlignmentX(LEFT_ALIGNMENT);
        refereePanel.setVisible(false);

        JLabel refTitle = new JLabel("REFEREE CONTROLS");
        refTitle.setForeground(COLOR_REFEREE);
        refTitle.setFont(FontManager.getRunescapeSmallFont());
        refTitle.setAlignmentX(LEFT_ALIGNMENT);
        refereePanel.add(refTitle);
        refereePanel.add(Box.createVerticalStrut(6));

        JLabel messageTitle = new JLabel("Broadcast Message");
        messageTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        messageTitle.setFont(FontManager.getRunescapeSmallFont());
        messageTitle.setAlignmentX(LEFT_ALIGNMENT);

        refereeMessagePanel.setLayout(new BoxLayout(refereeMessagePanel, BoxLayout.Y_AXIS));
        refereeMessagePanel.setBackground(new Color(24, 34, 26));
        refereeMessagePanel.setAlignmentX(LEFT_ALIGNMENT);

        refereeMessageField.setAlignmentX(LEFT_ALIGNMENT);
        refereeMessageField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        Runnable sendMessage = () ->
        {
            plugin.onBroadcastMessageClicked(refereeMessageField.getText());
            refereeMessageField.setText("");
        };
        refereeMessageField.addActionListener(e -> sendMessage.run());

        sendMessageBtn.setAlignmentX(LEFT_ALIGNMENT);
        sendMessageBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        sendMessageBtn.addActionListener(e -> sendMessage.run());

        refereeMessagePanel.add(refereeMessageField);
        refereeMessagePanel.add(Box.createVerticalStrut(4));
        refereeMessagePanel.add(sendMessageBtn);

        refereePanel.add(messageTitle);
        refereePanel.add(Box.createVerticalStrut(4));
        refereePanel.add(refereeMessagePanel);
        refereePanel.add(Box.createVerticalStrut(8));

        refereeActiveControlsPanel.setLayout(new BoxLayout(refereeActiveControlsPanel, BoxLayout.Y_AXIS));
        refereeActiveControlsPanel.setBackground(new Color(24, 34, 26));
        refereeActiveControlsPanel.setAlignmentX(LEFT_ALIGNMENT);
        refereeActiveControlsPanel.setVisible(false);

        whistleBtn.setAlignmentX(LEFT_ALIGNMENT);
        whistleBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        whistleBtn.setForeground(COLOR_BALL);
        whistleBtn.addActionListener(e -> plugin.onBlowWhistleClicked());

        timerToggleBtn.setAlignmentX(LEFT_ALIGNMENT);
        timerToggleBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        timerToggleBtn.addActionListener(e -> plugin.onTimerStartStopClicked());

        setClockBtn.setAlignmentX(LEFT_ALIGNMENT);
        setClockBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        setClockBtn.addActionListener(e -> showSetClockDialog());

        refereeActiveControlsPanel.add(whistleBtn);
        refereeActiveControlsPanel.add(Box.createVerticalStrut(4));
        refereeActiveControlsPanel.add(timerToggleBtn);
        refereeActiveControlsPanel.add(Box.createVerticalStrut(4));
        refereeActiveControlsPanel.add(setClockBtn);

        refereePanel.add(refereeActiveControlsPanel);
        card.add(refereePanel);
        card.add(Box.createVerticalStrut(4));

        // Leave game (all)
        leaveGameBtn.setAlignmentX(LEFT_ALIGNMENT);
        leaveGameBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        leaveGameBtn.setVisible(false);
        leaveGameBtn.addActionListener(e -> plugin.onLeaveClicked());
        card.add(Box.createVerticalStrut(4));
        card.add(leaveGameBtn);

        return card;
    }

    // -------------------------------------------------------------------------
    // Refresh
    // -------------------------------------------------------------------------

    public void refresh()
    {
        GamePhase phase   = plugin.getPhase();
        // A referee the host has appointed gets the full "Host Controls" card too now, not just
        // the separate "Referee Controls" one below -- see GnomeballPlugin#canActAsHost.
        boolean   canActAsHost = plugin.canActAsHost();
        String    jc      = plugin.getJoinCode();

        switch (phase)
        {
            case DISCONNECTED:
                cardLayout.show(cardPanel, "CONNECT");
                break;

            case LOBBY:
                cardLayout.show(cardPanel, "IN_GAME");
                joinCodeValueLabel.setText(jc != null ? jc : "—");
                refreshScoreboard();
                hostControlsCard.setVisible(canActAsHost);
                refreshGridButton();
                hostPreStartPanel.setVisible(canActAsHost);
                hostInGamePanel.setVisible(false);
                refereePanel.setVisible(plugin.isReferee());
                refereeActiveControlsPanel.setVisible(false);
                leaveGameBtn.setVisible(true);
                refreshRoster(plugin.getRoster().snapshot());
                break;

            case ACTIVE:
                cardLayout.show(cardPanel, "IN_GAME");
                joinCodeValueLabel.setText(jc != null ? jc : "—");
                refreshScoreboard();
                hostControlsCard.setVisible(canActAsHost);
                refreshGridButton();
                hostPreStartPanel.setVisible(false);
                hostInGamePanel.setVisible(canActAsHost);
                refereePanel.setVisible(plugin.isReferee());
                refereeActiveControlsPanel.setVisible(true);
                timerToggleBtn.setText(plugin.isTimerPaused() ? "START Clock" : "STOP Clock");
                leaveGameBtn.setVisible(true);
                refreshRoster(plugin.getRoster().snapshot());
                break;

            case ENDED:
                cardLayout.show(cardPanel, "IN_GAME");
                refreshScoreboard();
                hostControlsCard.setVisible(false);
                refereePanel.setVisible(false);
                leaveGameBtn.setVisible(true);
                refreshRoster(plugin.getRoster().snapshot());
                break;
        }
    }

    private void refreshRoster(List<RosterReducer.RosterEntry> entries)
    {
        // Team colors are included here too -- the roster rows' role-colored number labels (see
        // roleColor()) come straight from plugin.getTeamAColor()/getTeamBColor(), so a color
        // change with nothing else different (same players, same names/phase/ball holder) would
        // otherwise leave this cache key unchanged and the rebuild below would never run.
        String key = buildRosterKey(entries) + plugin.getTeamAName() + '|' + plugin.getTeamBName() + '|'
            + plugin.getTeamAColorHex() + '|' + plugin.getTeamBColorHex() + '|' + plugin.getPhase() + '|' + plugin.getBallHolder();
        if (key.equals(lastRosterKey)) return;
        lastRosterKey = key;

        rosterTablePanel.removeAll();

        JPanel header = new JPanel(new GridLayout(1, 2));
        header.setBackground(new Color(30, 30, 30));
        header.setBorder(new EmptyBorder(3, 6, 3, 6));
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        JLabel numHeader = new JLabel("#");
        numHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        numHeader.setFont(FontManager.getRunescapeSmallFont());
        JLabel nameHeader = new JLabel("Player");
        nameHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        nameHeader.setFont(FontManager.getRunescapeSmallFont());
        header.add(numHeader);
        header.add(nameHeader);
        rosterTablePanel.add(header);

        for (int i = 0; i < entries.size(); i++)
        {
            RosterReducer.RosterEntry entry = entries.get(i);
            JPanel row = new JPanel(new GridLayout(1, 2));
            row.setBackground(i % 2 == 0 ? ROW_EVEN : ROW_ODD);
            row.setBorder(new EmptyBorder(3, 6, 3, 6));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

            Color roleColor = roleColor(entry.role);
            Color nameColor = entry.joined ? Color.WHITE : ColorScheme.MEDIUM_GRAY_COLOR;

            JLabel numLabel = new JLabel(entry.role == GnomeballRole.REFEREE ? "Ref" : entry.number);
            numLabel.setForeground(roleColor);
            numLabel.setFont(FontManager.getRunescapeSmallFont());

            JLabel nameLabel = new JLabel(entry.rsn);
            nameLabel.setForeground(nameColor);
            nameLabel.setFont(FontManager.getRunescapeSmallFont());

            row.add(numLabel);
            row.add(nameLabel);

            // Any referee the host has appointed gets the full role/ball management menu now too
            // (see canActAsHost() / buildRolePopup). A plain enlisted player who is neither the
            // host nor a referee still gets the popup for their own row, but only for its Change
            // Number entry.
            String myRsn = plugin.getLocalRsn();
            boolean isOwnEnlistedRow = myRsn != null && myRsn.equalsIgnoreCase(entry.rsn)
                && (entry.role == GnomeballRole.TEAM_A || entry.role == GnomeballRole.TEAM_B);
            if (plugin.canActAsHost() || isOwnEnlistedRow)
            {
                JPopupMenu popup = buildRolePopup(entry.rsn, entry.role, entry.number);
                attachPopup(row, popup);
                attachPopup(numLabel, popup);
                attachPopup(nameLabel, popup);

                String hint = "Right-click to manage " + entry.rsn;
                row.setToolTipText(hint);
                numLabel.setToolTipText(hint);
                nameLabel.setToolTipText(hint);
            }

            rosterTablePanel.add(row);
        }

        rosterTablePanel.revalidate();
        rosterTablePanel.repaint();
    }

    private JPopupMenu buildRolePopup(String rsn, GnomeballRole current, String number)
    {
        JPopupMenu popup = new JPopupMenu();
        String localRsn = plugin.getLocalRsn();
        boolean isSelf = localRsn != null && rsn.equalsIgnoreCase(localRsn);

        // Self-service -- any enlisted team player can pick their own number, whether or not
        // they're also the host/a referee. Not offered to referees/observers (see change_number
        // server-side, which rejects them too -- they have no jersey number to begin with).
        if (isSelf && (current == GnomeballRole.TEAM_A || current == GnomeballRole.TEAM_B))
        {
            JMenuItem changeNumber = new JMenuItem("Change Number");
            changeNumber.addActionListener(e -> showChangeNumberDialog(number));
            popup.add(changeNumber);
        }

        // Ball/role management now has full host/referee parity -- any referee the host has
        // appointed can use this too, not just the write-key-holding host (see
        // GnomeballPlugin#canActAsHost / the server's require_host_or_referee).
        if (plugin.canActAsHost())
        {
            if (popup.getComponentCount() > 0) popup.addSeparator();
            if (plugin.getPhase() == GamePhase.ACTIVE)
            {
                String bh = plugin.getBallHolder();
                boolean hasBall = bh != null && bh.equalsIgnoreCase(rsn);
                if (hasBall)
                {
                    JMenuItem removeBall = new JMenuItem("Remove Ball");
                    removeBall.addActionListener(e -> plugin.onClearBallClicked());
                    popup.add(removeBall);
                }
                else
                {
                    JMenuItem assignBall = new JMenuItem("Assign Ball");
                    assignBall.addActionListener(e -> plugin.onAssignBallClicked(rsn));
                    popup.add(assignBall);
                }
                popup.addSeparator();
            }

            for (GnomeballRole role : GnomeballRole.values())
            {
                if (role == current) continue;
                JMenuItem item = new JMenuItem("Switch to " + roleDisplayName(role));
                item.setForeground(roleColor(role));
                item.addActionListener(e -> plugin.onAssignRoleClicked(rsn, role));
                popup.add(item);
            }
        }

        // Kick is referee-gated server-side (authenticates as the kicking referee's own session
        // token, not the write key), so any referee sees it here -- not just the host. Frees a
        // slot a disconnected player left stuck without waiting for them, or removes a rogue
        // player. Not offered against yourself -- use Leave Game for that.
        if (plugin.isReferee() && !isSelf)
        {
            if (popup.getComponentCount() > 0) popup.addSeparator();
            JMenuItem kick = new JMenuItem("Kick " + rsn);
            kick.setForeground(Color.RED);
            kick.addActionListener(e ->
            {
                int choice = JOptionPane.showConfirmDialog(this,
                    "Remove " + rsn + " from the game? They'll need to rejoin with the game code.",
                    "Kick Player", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (choice == JOptionPane.YES_OPTION) plugin.onKickPlayerClicked(rsn);
            });
            popup.add(kick);
        }

        return popup;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Prompts the referee for a minutes/seconds remaining value, prefilled with the clock's
     * current reading (paused or live), and pushes it via {@link GnomeballPlugin#onSetClockClicked}
     * if confirmed. */
    private void showSetClockDialog()
    {
        long currentRemainingMs = plugin.isTimerPaused()
            ? plugin.getPausedRemainingMs()
            : Math.max(0, plugin.getDeadlineMs() - System.currentTimeMillis());
        int totalSecs = (int) (currentRemainingMs / 1000);

        JSpinner minSpinner = new JSpinner(new SpinnerNumberModel(totalSecs / 60, 0, 180, 1));
        JSpinner secSpinner = new JSpinner(new SpinnerNumberModel(totalSecs % 60, 0, 59, 1));
        ((JSpinner.DefaultEditor) minSpinner.getEditor()).getTextField().setColumns(3);
        ((JSpinner.DefaultEditor) secSpinner.getEditor()).getTextField().setColumns(3);

        JPanel dialogPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        dialogPanel.add(new JLabel("Min:"));
        dialogPanel.add(minSpinner);
        dialogPanel.add(new JLabel("Sec:"));
        dialogPanel.add(secSpinner);

        int choice = JOptionPane.showConfirmDialog(this, dialogPanel, "Set Clock",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;

        int minutes = (Integer) minSpinner.getValue();
        int seconds = (Integer) secSpinner.getValue();
        plugin.onSetClockClicked((minutes * 60L + seconds) * 1000L);
    }

    /** Prompts the player for a new jersey number, prefilled with their current one, and pushes
     * it via {@link GnomeballPlugin#onChangeNumberClicked}. Availability is validated server-side
     * -- a taken number comes back as a chat message (see ApiClient#changeNumber), not a dialog
     * error, since the rejection arrives asynchronously after this dialog has already closed. */
    private void showChangeNumberDialog(String currentNumber)
    {
        int current = 1;
        if (currentNumber != null && currentNumber.startsWith("#"))
        {
            try { current = Integer.parseInt(currentNumber.substring(1)); }
            catch (NumberFormatException ignored) { }
        }

        JSpinner numberSpinner = new JSpinner(new SpinnerNumberModel(current, 1, 99, 1));
        ((JSpinner.DefaultEditor) numberSpinner.getEditor()).getTextField().setColumns(3);

        JPanel dialogPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        dialogPanel.add(new JLabel("Number:"));
        dialogPanel.add(numberSpinner);

        int choice = JOptionPane.showConfirmDialog(this, dialogPanel, "Change Number",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;

        plugin.onChangeNumberClicked((Integer) numberSpinner.getValue());
    }

    private static final int CUSTOM_GRID_INDEX = 0;

    private void refreshGridButton()
    {
        gridSizeRow.setVisible(presetDropdown.getSelectedIndex() == CUSTOM_GRID_INDEX);

        FieldPreset resolved = resolveSelectedPreset();
        boolean hasValidSelection = resolved != null && !resolved.isEmpty();

        placePresetBtn.setText(plugin.isPresetPlacementMode() ? "Cancel" : "Place");
        placePresetBtn.setEnabled(plugin.isPresetPlacementMode() || hasValidSelection);

        removePresetBtn.setText(plugin.isPresetRemovalMode() ? "Cancel" : "Remove");
        removePresetBtn.setEnabled(plugin.isPresetRemovalMode() || hasValidSelection);

        boolean hasFieldTiles = !plugin.getTileReducer().fieldSnapshot().isEmpty();
        int slotIndex = resolveSelectedCustomSlotIndex();
        saveFieldBtn.setEnabled(slotIndex >= 0 && hasFieldTiles);
        clearArenaBtn.setEnabled(hasFieldTiles);
        removeFlagsBtn.setEnabled(plugin.hasFlags());
    }

    /** Rebuilds the preset dropdown's item labels ("Custom Grid" + built-ins + custom slots,
     * "(empty)" suffix for unpopulated slots) while preserving the current selection. Called
     * only right after a Save action and once at construction — not from refresh(), to avoid
     * disrupting an open dropdown mid-interaction. */
    private void refreshPresetDropdownItems()
    {
        int selectedIndex = presetDropdown.getSelectedIndex();
        presetDropdown.removeAllItems();
        presetDropdown.addItem("Custom Grid");
        for (FieldPreset preset : FieldPreset.ALL) presetDropdown.addItem(preset.name);
        for (int i = 0; i < GnomeballPlugin.getCustomSlotCount(); i++)
        {
            FieldPreset slot = plugin.getCustomSlot(i);
            presetDropdown.addItem(slot != null ? slot.name : "Custom Slot " + (i + 1) + " (empty)");
        }
        presetDropdown.setSelectedIndex(Math.max(selectedIndex, 0));
    }

    /** Resolves the dropdown's current selection to a placeable preset. "Custom Grid" is
     * generated fresh from the current spinner values every call — cheap, and keeps it always
     * in sync without needing to rebuild the dropdown when the spinners change. Returns null only
     * if an empty custom slot is selected. */
    private FieldPreset resolveSelectedPreset()
    {
        int idx = presetDropdown.getSelectedIndex();
        // -1 = no selection, which happens transiently while refreshPresetDropdownItems() clears
        // the combo box (removeAllItems() fires the selection listener synchronously mid-rebuild).
        if (idx < 0) return null;
        if (idx == CUSTOM_GRID_INDEX)
        {
            return FieldPreset.customGrid((Integer) gridWidthSpinner.getValue(), (Integer) gridHeightSpinner.getValue());
        }
        int builtInCount = FieldPreset.ALL.size();
        if (idx <= builtInCount) return FieldPreset.ALL.get(idx - 1);
        return plugin.getCustomSlot(idx - builtInCount - 1);
    }

    /** Resolves the dropdown's current selection to a custom-slot index (0-based), or -1 if
     * "Custom Grid" or a built-in preset is selected. Populated vs. empty is not distinguished
     * here — Save should be offered for either, since saving IS what populates an empty slot. */
    private int resolveSelectedCustomSlotIndex()
    {
        int idx = presetDropdown.getSelectedIndex();
        int builtInCount = FieldPreset.ALL.size();
        int slotIndex = idx - builtInCount - 1;
        return (slotIndex >= 0 && slotIndex < GnomeballPlugin.getCustomSlotCount()) ? slotIndex : -1;
    }

    private void commitTeamName(String team, String name)
    {
        if (name.isEmpty()) return;
        plugin.onRenameTeam(team, name);
    }

    /** Sets up a small clickable color square -- background/tooltip/cursor are kept current by
     * refreshScoreboard(); this just wires the click itself, which opens a JColorChooser and
     * pushes the pick to the server. Re-checks isReferee() at click time (not just cursor/tooltip
     * state) since role can change between when the panel last refreshed and when it's clicked. */
    private void wireColorSwatch(JPanel swatch, String team)
    {
        Dimension size = new Dimension(16, 16);
        swatch.setPreferredSize(size);
        swatch.setMinimumSize(size);
        swatch.setMaximumSize(size);
        swatch.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1));
        swatch.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                if (!plugin.isReferee()) return;
                String teamName = "TEAM_A".equals(team) ? plugin.getTeamAName() : plugin.getTeamBName();
                Color current = "TEAM_A".equals(team) ? plugin.getTeamAColor() : plugin.getTeamBColor();
                Color chosen = JColorChooser.showDialog(GnomeballPanel.this, "Choose " + teamName + " Color", current);
                if (chosen != null) plugin.onSetTeamColorClicked(team, chosen);
            }
        });
    }

    private void refreshScoreboard()
    {
        boolean canActAsHost = plugin.canActAsHost();
        boolean isReferee = plugin.isReferee();
        Color teamAColor = plugin.getTeamAColor();
        Color teamBColor = plugin.getTeamBColor();

        if (!teamANameField.isFocusOwner())
            teamANameField.setText(plugin.getTeamAName());
        if (!teamBNameField.isFocusOwner())
            teamBNameField.setText(plugin.getTeamBName());

        teamANameField.setEditable(canActAsHost);
        teamBNameField.setEditable(canActAsHost);
        teamANameField.setForeground(teamAColor);
        teamBNameField.setForeground(teamBColor);

        scoreALabel.setText(String.valueOf(plugin.getTeamAScore()));
        scoreBLabel.setText(String.valueOf(plugin.getTeamBScore()));
        scoreALabel.setForeground(teamAColor);
        scoreBLabel.setForeground(teamBColor);
        scoreAMinus.setForeground(teamAColor);
        scoreAPlus.setForeground(teamAColor);
        scoreBMinus.setForeground(teamBColor);
        scoreBPlus.setForeground(teamBColor);

        teamAColorSwatch.setBackground(teamAColor);
        teamBColorSwatch.setBackground(teamBColor);
        String swatchHint = isReferee ? "Click to change" : "Referees can change this";
        teamAColorSwatch.setToolTipText(plugin.getTeamAName() + " color -- " + swatchHint);
        teamBColorSwatch.setToolTipText(plugin.getTeamBName() + " color -- " + swatchHint);
        teamAColorSwatch.setCursor(Cursor.getPredefinedCursor(isReferee ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        teamBColorSwatch.setCursor(Cursor.getPredefinedCursor(isReferee ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));

        GamePhase phase = plugin.getPhase();
        boolean showScoreBtns = canActAsHost && phase == GamePhase.ACTIVE;
        hostScoreAPanel.setVisible(showScoreBtns);
        hostScoreBPanel.setVisible(showScoreBtns);
    }

    private Color roleColor(GnomeballRole role)
    {
        switch (role)
        {
            case REFEREE:  return COLOR_REFEREE;
            case TEAM_A:   return plugin.getTeamAColor();
            case TEAM_B:   return plugin.getTeamBColor();
            case OBSERVER: return ColorScheme.MEDIUM_GRAY_COLOR;
            default:       return Color.WHITE;
        }
    }

    private String roleDisplayName(GnomeballRole role)
    {
        switch (role)
        {
            case REFEREE:  return "Referee";
            case TEAM_A:   return plugin.getTeamAName();
            case TEAM_B:   return plugin.getTeamBName();
            case OBSERVER: return "Observer";
            default:       return role.name();
        }
    }

    private static String buildRosterKey(List<RosterReducer.RosterEntry> entries)
    {
        StringBuilder sb = new StringBuilder();
        for (RosterReducer.RosterEntry e : entries)
            sb.append(e.rsn).append(':').append(e.role).append(':').append(e.online).append(':').append(e.number).append(':').append(e.joined).append(';');
        return sb.toString();
    }

    private static void copyToClipboard(String text)
    {
        if (text == null || text.isEmpty()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(text), null);
    }

    private static void attachPopup(JComponent c, JPopupMenu popup)
    {
        c.addMouseListener(new MouseAdapter()
        {
            @Override public void mousePressed(MouseEvent e)  { if (e.isPopupTrigger()) popup.show(c, e.getX(), e.getY()); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) popup.show(c, e.getX(), e.getY()); }
        });
    }
}