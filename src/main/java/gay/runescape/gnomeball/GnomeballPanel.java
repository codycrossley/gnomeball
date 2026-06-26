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
    private static final Color COLOR_REFEREE = new Color(220, 180, 40);
    private static final Color COLOR_TEAM_A  = new Color(60, 120, 220);
    private static final Color COLOR_TEAM_B  = new Color(200, 60, 60);
    private static final Color ROW_EVEN      = new Color(40, 40, 40);
    private static final Color ROW_ODD       = new Color(50, 50, 50);

    private final GnomeballPlugin plugin;

    private final JLabel statusPill = new JLabel("Disconnected");

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

    // Grid placement (host)
    private final JPanel gridPanel = new JPanel();
    private final JSpinner gridWidthSpinner  = new JSpinner(new SpinnerNumberModel(5, 1, 50, 1));
    private final JSpinner gridHeightSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 50, 1));
    private final JButton placeGridBtn  = new JButton("Place Grid");
    private final JButton removeGridBtn = new JButton("Remove Grid");
    private final JButton zoneABtn = new JButton("Team A Zone");
    private final JButton zoneBBtn = new JButton("Team B Zone");

    // Host pre-start (LOBBY only)
    private final JPanel hostPreStartPanel = new JPanel();
    private final JSpinner durationSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 120, 1));
    private final JButton startGameBtn     = new JButton("Start Game");

    // Host in-game (ACTIVE only)
    private final JButton endGameBtn   = new JButton("End Game");

    // Referee controls (ACTIVE, referee role)
    private final JPanel  refereePanel    = new JPanel();
    private final JButton whistleBtn      = new JButton("Blow Whistle");
    private final JButton timerToggleBtn  = new JButton("STOP");

    // All players
    private final JButton leaveGameBtn = new JButton("Leave Game");

    private String lastRosterKey = null;

    public GnomeballPanel(GnomeballPlugin plugin)
    {
        super(false);
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

        statusPill.setOpaque(true);
        statusPill.setBorder(new EmptyBorder(3, 8, 3, 8));
        statusPill.setHorizontalAlignment(SwingConstants.CENTER);
        setStatus("Disconnected", ColorScheme.MEDIUM_GRAY_COLOR);
        header.add(statusPill, BorderLayout.EAST);

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

        // Roster table
        rosterTablePanel.setLayout(new BoxLayout(rosterTablePanel, BoxLayout.Y_AXIS));
        rosterTablePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        JScrollPane scroll = new JScrollPane(rosterTablePanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setAlignmentX(LEFT_ALIGNMENT);
        scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        card.add(scroll);
        card.add(Box.createVerticalStrut(12));

        // Grid placement (host)
        JLabel gridTitle = new JLabel("FIELD");
        gridTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        gridTitle.setFont(FontManager.getRunescapeSmallFont());
        gridTitle.setAlignmentX(LEFT_ALIGNMENT);

        gridPanel.setLayout(new BoxLayout(gridPanel, BoxLayout.Y_AXIS));
        gridPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        gridPanel.setAlignmentX(LEFT_ALIGNMENT);
        gridPanel.setVisible(false);

        JPanel gridSizeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        gridSizeRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
        gridSizeRow.setAlignmentX(LEFT_ALIGNMENT);
        JLabel wLabel = new JLabel("W:");
        wLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        gridSizeRow.add(wLabel);
        gridWidthSpinner.setPreferredSize(new Dimension(50, 24));
        gridSizeRow.add(gridWidthSpinner);
        gridSizeRow.add(Box.createHorizontalStrut(6));
        JLabel hLabel = new JLabel("H:");
        hLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        gridSizeRow.add(hLabel);
        gridHeightSpinner.setPreferredSize(new Dimension(50, 24));
        gridSizeRow.add(gridHeightSpinner);
        gridPanel.add(gridSizeRow);
        gridPanel.add(Box.createVerticalStrut(4));

        JPanel gridBtnRow = new JPanel(new GridLayout(1, 2, 4, 0));
        gridBtnRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
        gridBtnRow.setAlignmentX(LEFT_ALIGNMENT);
        gridBtnRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        placeGridBtn.addActionListener(e ->
        {
            if (plugin.isGridPlacementMode())
            {
                plugin.cancelGridMode();
            }
            else
            {
                plugin.startGridPlacement((Integer) gridWidthSpinner.getValue(), (Integer) gridHeightSpinner.getValue());
            }
            refreshGridButton();
        });

        removeGridBtn.setForeground(new Color(220, 60, 60));
        removeGridBtn.addActionListener(e ->
        {
            if (plugin.isGridRemovalMode())
            {
                plugin.cancelGridMode();
            }
            else
            {
                plugin.startGridRemoval((Integer) gridWidthSpinner.getValue(), (Integer) gridHeightSpinner.getValue());
            }
            refreshGridButton();
        });

        gridBtnRow.add(placeGridBtn);
        gridBtnRow.add(removeGridBtn);
        gridPanel.add(gridBtnRow);
        gridPanel.add(Box.createVerticalStrut(4));

        JPanel zoneBtnRow = new JPanel(new GridLayout(1, 2, 4, 0));
        zoneBtnRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
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
        gridPanel.add(zoneBtnRow);

        card.add(gridTitle);
        card.add(Box.createVerticalStrut(4));
        card.add(gridPanel);
        card.add(Box.createVerticalStrut(8));

        // Host pre-start controls
        hostPreStartPanel.setLayout(new BoxLayout(hostPreStartPanel, BoxLayout.Y_AXIS));
        hostPreStartPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        hostPreStartPanel.setAlignmentX(LEFT_ALIGNMENT);
        hostPreStartPanel.setVisible(false);

        JPanel durationRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        durationRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
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
        card.add(hostPreStartPanel);
        card.add(Box.createVerticalStrut(4));

        // End game (ACTIVE, host)
        endGameBtn.setAlignmentX(LEFT_ALIGNMENT);
        endGameBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        endGameBtn.setForeground(new Color(220, 60, 60));
        endGameBtn.setVisible(false);
        endGameBtn.addActionListener(e -> plugin.onEndClicked());
        card.add(endGameBtn);

        // Referee controls (ACTIVE, referee role)
        refereePanel.setLayout(new BoxLayout(refereePanel, BoxLayout.Y_AXIS));
        refereePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        refereePanel.setAlignmentX(LEFT_ALIGNMENT);
        refereePanel.setVisible(false);

        JLabel refTitle = new JLabel("REFEREE");
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
        refereePanel.add(Box.createVerticalStrut(4));
        refereePanel.add(whistleBtn);
        refereePanel.add(Box.createVerticalStrut(4));
        refereePanel.add(timerToggleBtn);
        card.add(Box.createVerticalStrut(4));
        card.add(refereePanel);

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
                setStatus("Disconnected", ColorScheme.MEDIUM_GRAY_COLOR);
                cardLayout.show(cardPanel, "CONNECT");
                break;

            case LOBBY:
                setStatus("Lobby", new Color(180, 140, 40));
                cardLayout.show(cardPanel, "IN_GAME");
                joinCodeValueLabel.setText(jc != null ? jc : "—");
                refreshScoreboard();
                gridPanel.setVisible(isHost);
                refreshGridButton();
                hostPreStartPanel.setVisible(isHost);
                endGameBtn.setVisible(false);
                refereePanel.setVisible(false);
                leaveGameBtn.setVisible(true);
                refreshRoster(plugin.getRoster().snapshot());
                break;

            case ACTIVE:
                setStatus("In Game", new Color(60, 180, 60));
                cardLayout.show(cardPanel, "IN_GAME");
                joinCodeValueLabel.setText(jc != null ? jc : "—");
                refreshScoreboard();
                gridPanel.setVisible(isHost);
                refreshGridButton();
                hostPreStartPanel.setVisible(false);
                endGameBtn.setVisible(isHost);
                refereePanel.setVisible(plugin.isReferee());
                timerToggleBtn.setText(plugin.isTimerPaused() ? "START" : "STOP");
                leaveGameBtn.setVisible(true);
                refreshRoster(plugin.getRoster().snapshot());
                break;

            case ENDED:
                setStatus("Ended", new Color(140, 60, 60));
                cardLayout.show(cardPanel, "IN_GAME");
                refreshScoreboard();
                hostPreStartPanel.setVisible(false);
                endGameBtn.setVisible(false);
                refereePanel.setVisible(false);
                leaveGameBtn.setVisible(true);
                refreshRoster(plugin.getRoster().snapshot());
                break;
        }
    }

    private void refreshRoster(List<RosterReducer.RosterEntry> entries)
    {
        String key = buildRosterKey(entries) + plugin.getTeamAName() + '|' + plugin.getTeamBName();
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

            JLabel numLabel = new JLabel(entry.number);
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
            }

            rosterTablePanel.add(row);
        }

        rosterTablePanel.revalidate();
        rosterTablePanel.repaint();
    }

    private JPopupMenu buildRolePopup(String rsn, GnomeballRole current)
    {
        JPopupMenu popup = new JPopupMenu();
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

    private void refreshGridButton()
    {
        placeGridBtn.setText(plugin.isGridPlacementMode() ? "Cancel" : "Place");
        removeGridBtn.setText(plugin.isGridRemovalMode() ? "Cancel" : "Remove");

        boolean inZoneA = plugin.isZoneMode() && "TEAM_A".equals(plugin.getZoneTeam());
        boolean inZoneB = plugin.isZoneMode() && "TEAM_B".equals(plugin.getZoneTeam());
        zoneABtn.setText(inZoneA ? "Cancel" : plugin.getTeamAName() + " Zone");
        zoneBBtn.setText(inZoneB ? "Cancel" : plugin.getTeamBName() + " Zone");
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

    private void setStatus(String text, Color bg)
    {
        statusPill.setText(text);
        statusPill.setBackground(bg);
        statusPill.setForeground(Color.WHITE);
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