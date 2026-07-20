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
    private static final Color COLOR_TEAM_A  = new Color(60, 120, 220);
    private static final Color COLOR_TEAM_B  = new Color(200, 60, 60);
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

    // Zones (host; shown whenever the host card is shown)
    private final JPanel zonePanel = new JPanel();
    private final JButton zoneABtn = new JButton("Team A Zone");
    private final JButton zoneBBtn = new JButton("Team B Zone");
    private final JButton clearArenaBtn = new JButton("Clear Current Arena");

    // Host pre-start (LOBBY only, within host card)
    private final JPanel hostPreStartPanel = new JPanel();
    private final JSpinner durationSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 120, 1));
    private final JButton startGameBtn     = new JButton("Start Game");

    // Host in-game controls (ACTIVE only, within host card)
    private final JPanel hostInGamePanel = new JPanel();
    private final JButton endGameBtn   = new JButton("End Game");
    private final JPanel  hostMessagePanel = new JPanel();
    private final JTextField hostMessageField = new JTextField();
    private final JButton sendMessageBtn = new JButton("Send Message");

    // Referee controls (ACTIVE, referee role)
    private final JPanel  refereePanel    = new JPanel();
    private final JButton whistleBtn      = new JButton("Blow Whistle");
    private final JButton timerToggleBtn  = new JButton("STOP");

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

        // Team A name
        teamANameField.setHorizontalAlignment(SwingConstants.CENTER);
        teamANameField.setForeground(COLOR_TEAM_A);
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
        scoreboardPanel.add(teamANameField);

        // Team A score row
        JPanel scoreARow = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 2));
        scoreARow.setBackground(new Color(30, 30, 30));
        scoreARow.setAlignmentX(CENTER_ALIGNMENT);
        scoreARow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        scoreALabel.setForeground(COLOR_TEAM_A);
        scoreALabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
        Insets btnInsets = new Insets(1, 6, 1, 6);
        scoreAMinus.setMargin(btnInsets); scoreAMinus.setForeground(COLOR_TEAM_A);
        scoreAPlus.setMargin(btnInsets);  scoreAPlus.setForeground(COLOR_TEAM_A);
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
        scoreBLabel.setForeground(COLOR_TEAM_B);
        scoreBLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
        scoreBMinus.setMargin(btnInsets); scoreBMinus.setForeground(COLOR_TEAM_B);
        scoreBPlus.setMargin(btnInsets);  scoreBPlus.setForeground(COLOR_TEAM_B);
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

        // Team B name
        teamBNameField.setHorizontalAlignment(SwingConstants.CENTER);
        teamBNameField.setForeground(COLOR_TEAM_B);
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
        scoreboardPanel.add(teamBNameField);
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

        // Zones — shown whenever the host card is shown. Kept separate from Field Presets since
        // zones are marked tile-by-tile per team rather than "placed" as a shape, and a
        // Custom-Grid field has no endzones of its own, so this is how those get added.
        JLabel zoneTitle = new JLabel("Zones");
        zoneTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        zoneTitle.setFont(FontManager.getRunescapeSmallFont());
        zoneTitle.setAlignmentX(LEFT_ALIGNMENT);

        zonePanel.setLayout(new BoxLayout(zonePanel, BoxLayout.Y_AXIS));
        zonePanel.setBackground(new Color(34, 30, 26));
        zonePanel.setAlignmentX(LEFT_ALIGNMENT);

        JPanel zoneBtnRow = new JPanel(new GridLayout(1, 2, 4, 0));
        zoneBtnRow.setBackground(new Color(34, 30, 26));
        zoneBtnRow.setAlignmentX(LEFT_ALIGNMENT);
        zoneBtnRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        zoneABtn.setForeground(COLOR_TEAM_A);
        zoneABtn.addActionListener(e ->
        {
            if (plugin.isZoneMode() && "TEAM_A".equals(plugin.getZoneTeam()))
                plugin.cancelZoneMode();
            else
                plugin.startZoneMode("TEAM_A");
            refreshGridButton();
        });

        zoneBBtn.setForeground(COLOR_TEAM_B);
        zoneBBtn.addActionListener(e ->
        {
            if (plugin.isZoneMode() && "TEAM_B".equals(plugin.getZoneTeam()))
                plugin.cancelZoneMode();
            else
                plugin.startZoneMode("TEAM_B");
            refreshGridButton();
        });

        zoneBtnRow.add(zoneABtn);
        zoneBtnRow.add(zoneBBtn);
        zonePanel.add(zoneBtnRow);

        hostControlsCard.add(zoneTitle);
        hostControlsCard.add(Box.createVerticalStrut(4));
        hostControlsCard.add(zonePanel);
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

        // In-game sub-group (ACTIVE only): end game + broadcast message
        hostInGamePanel.setLayout(new BoxLayout(hostInGamePanel, BoxLayout.Y_AXIS));
        hostInGamePanel.setBackground(new Color(34, 30, 26));
        hostInGamePanel.setAlignmentX(LEFT_ALIGNMENT);
        hostInGamePanel.setVisible(false);

        endGameBtn.setAlignmentX(LEFT_ALIGNMENT);
        endGameBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        endGameBtn.setForeground(new Color(220, 60, 60));
        endGameBtn.addActionListener(e -> plugin.onEndClicked());
        hostInGamePanel.add(endGameBtn);
        hostInGamePanel.add(Box.createVerticalStrut(8));

        JLabel messageTitle = new JLabel("Broadcast Message");
        messageTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        messageTitle.setFont(FontManager.getRunescapeSmallFont());
        messageTitle.setAlignmentX(LEFT_ALIGNMENT);

        hostMessagePanel.setLayout(new BoxLayout(hostMessagePanel, BoxLayout.Y_AXIS));
        hostMessagePanel.setBackground(new Color(34, 30, 26));
        hostMessagePanel.setAlignmentX(LEFT_ALIGNMENT);

        hostMessageField.setAlignmentX(LEFT_ALIGNMENT);
        hostMessageField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        Runnable sendMessage = () ->
        {
            plugin.onBroadcastMessageClicked(hostMessageField.getText());
            hostMessageField.setText("");
        };
        hostMessageField.addActionListener(e -> sendMessage.run());

        sendMessageBtn.setAlignmentX(LEFT_ALIGNMENT);
        sendMessageBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        sendMessageBtn.addActionListener(e -> sendMessage.run());

        hostMessagePanel.add(hostMessageField);
        hostMessagePanel.add(Box.createVerticalStrut(4));
        hostMessagePanel.add(sendMessageBtn);

        hostInGamePanel.add(messageTitle);
        hostInGamePanel.add(Box.createVerticalStrut(4));
        hostInGamePanel.add(hostMessagePanel);
        hostControlsCard.add(hostInGamePanel);

        card.add(hostControlsCard);
        card.add(Box.createVerticalStrut(8));

        // ===== REFEREE CONTROLS card (referee role only, ACTIVE) =====
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

        whistleBtn.setAlignmentX(LEFT_ALIGNMENT);
        whistleBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        whistleBtn.setForeground(COLOR_REFEREE);
        whistleBtn.addActionListener(e -> plugin.onBlowWhistleClicked());

        timerToggleBtn.setAlignmentX(LEFT_ALIGNMENT);
        timerToggleBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        timerToggleBtn.addActionListener(e -> plugin.onTimerStartStopClicked());

        refereePanel.add(refTitle);
        refereePanel.add(Box.createVerticalStrut(6));
        refereePanel.add(whistleBtn);
        refereePanel.add(Box.createVerticalStrut(4));
        refereePanel.add(timerToggleBtn);
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
        boolean   isHost  = plugin.isHost();
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
                hostControlsCard.setVisible(isHost);
                refreshGridButton();
                hostPreStartPanel.setVisible(isHost);
                hostInGamePanel.setVisible(false);
                refereePanel.setVisible(false);
                leaveGameBtn.setVisible(true);
                refreshRoster(plugin.getRoster().snapshot());
                break;

            case ACTIVE:
                cardLayout.show(cardPanel, "IN_GAME");
                joinCodeValueLabel.setText(jc != null ? jc : "—");
                refreshScoreboard();
                hostControlsCard.setVisible(isHost);
                refreshGridButton();
                hostPreStartPanel.setVisible(false);
                hostInGamePanel.setVisible(isHost);
                refereePanel.setVisible(plugin.isReferee());
                timerToggleBtn.setText(plugin.isTimerPaused() ? "START" : "STOP");
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
        String key = buildRosterKey(entries) + plugin.getTeamAName() + '|' + plugin.getTeamBName() + '|' + plugin.getPhase() + '|' + plugin.getBallHolder();
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

            if (plugin.isHost())
            {
                JPopupMenu popup = buildRolePopup(entry.rsn, entry.role);
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

    private JPopupMenu buildRolePopup(String rsn, GnomeballRole current)
    {
        JPopupMenu popup = new JPopupMenu();

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
        return popup;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static final int CUSTOM_GRID_INDEX = 0;

    private void refreshGridButton()
    {
        gridSizeRow.setVisible(presetDropdown.getSelectedIndex() == CUSTOM_GRID_INDEX);

        boolean inZoneA = plugin.isZoneMode() && "TEAM_A".equals(plugin.getZoneTeam());
        boolean inZoneB = plugin.isZoneMode() && "TEAM_B".equals(plugin.getZoneTeam());
        zoneABtn.setText(inZoneA ? "Cancel" : "Team A Zone");
        zoneBBtn.setText(inZoneB ? "Cancel" : "Team B Zone");

        FieldPreset resolved = resolveSelectedPreset();
        boolean hasValidSelection = resolved != null && !resolved.isEmpty();

        placePresetBtn.setText(plugin.isPresetPlacementMode() ? "Cancel" : "Place");
        placePresetBtn.setEnabled(plugin.isPresetPlacementMode() || hasValidSelection);

        removePresetBtn.setText(plugin.isPresetRemovalMode() ? "Cancel" : "Remove");
        removePresetBtn.setEnabled(plugin.isPresetRemovalMode() || hasValidSelection);

        boolean hasFieldTiles = !plugin.getTileReducer().snapshot().isEmpty();
        int slotIndex = resolveSelectedCustomSlotIndex();
        saveFieldBtn.setEnabled(slotIndex >= 0 && hasFieldTiles);
        clearArenaBtn.setEnabled(hasFieldTiles);
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

    private void refreshScoreboard()
    {
        boolean isHost = plugin.isHost();

        if (!teamANameField.isFocusOwner())
            teamANameField.setText(plugin.getTeamAName());
        if (!teamBNameField.isFocusOwner())
            teamBNameField.setText(plugin.getTeamBName());

        teamANameField.setEditable(isHost);
        teamBNameField.setEditable(isHost);

        scoreALabel.setText(String.valueOf(plugin.getTeamAScore()));
        scoreBLabel.setText(String.valueOf(plugin.getTeamBScore()));

        GamePhase phase = plugin.getPhase();
        boolean showScoreBtns = isHost && phase == GamePhase.ACTIVE;
        hostScoreAPanel.setVisible(showScoreBtns);
        hostScoreBPanel.setVisible(showScoreBtns);
    }

    private static Color roleColor(GnomeballRole role)
    {
        switch (role)
        {
            case REFEREE:  return COLOR_REFEREE;
            case TEAM_A:   return COLOR_TEAM_A;
            case TEAM_B:   return COLOR_TEAM_B;
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