package com.dependencyresolver.ui;

import com.dependencyresolver.bootstrap.FlowController;
import com.dependencyresolver.bootstrap.GateResult;
import com.dependencyresolver.bootstrap.LoadingState;
import com.dependencyresolver.dependency.Dependency;
import com.dependencyresolver.log.ResolverLog;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minecraft-styled window drawn with Java2D. Forge's own screens do not exist
 * until after the launch steps this bootstrap is required to pause.
 */
public final class DependencyWindow extends JFrame {
    private static final Font TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 22);
    private static final Font BODY = new Font(Font.SANS_SERIF, Font.PLAIN, 16);
    private final FlowController flow;
    private final CompletableFuture<GateResult> result;
    private final JLabel title = new JLabel("", JLabel.CENTER);
    private final JTextArea body = new JTextArea();
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JLabel status = new JLabel(" ");
    private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
    private final ExecutorService workers = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dependency-resolver");
        thread.setDaemon(true);
        return thread;
    });
    private FlowController.Snapshot snapshot;

    private DependencyWindow(FlowController flow, FlowController.Snapshot snapshot, CompletableFuture<GateResult> result) {
        super("Minecraft");
        this.flow = flow;
        this.snapshot = snapshot;
        this.result = result;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setContentPane(new DirtPanel());
        setMinimumSize(new Dimension(560, 420));
        setSize(640, 480);
        setLocationRelativeTo(null);
        setAlwaysOnTop(true);
        build();
        showSnapshot(snapshot);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                finish(GateResult.abort("Dependency installation was cancelled. Minecraft was not started.", 1));
            }
        });
    }

    public static GateResult show(FlowController flow, FlowController.Snapshot snapshot) throws Exception {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
            ResolverLog.debug("Using the default window theme.");
        }
        CompletableFuture<GateResult> result = new CompletableFuture<>();
        SwingUtilities.invokeAndWait(() -> {
            DependencyWindow window = new DependencyWindow(flow, snapshot, result);
            window.setVisible(true);
            window.toFront();
            window.requestFocus();
        });
        return result.get();
    }

    private void build() {
        JPanel card = new JPanel();
        Color cardColor = new Color(20, 20, 20);
        card.setOpaque(true);
        card.setBackground(cardColor);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x3F3F3F), 4),
                BorderFactory.createEmptyBorder(18, 22, 18, 22)));
        card.setLayout(new BorderLayout(0, 12));

        title.setForeground(Color.WHITE);
        title.setFont(TITLE);
        title.setOpaque(true);
        title.setBackground(cardColor);
        card.add(title, BorderLayout.NORTH);

        body.setEditable(false);
        body.setOpaque(true);
        body.setBackground(cardColor);
        body.setForeground(new Color(0xE0E0E0));
        body.setFont(BODY);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(body);
        scroll.setOpaque(true);
        scroll.setBackground(cardColor);
        scroll.getViewport().setOpaque(true);
        scroll.getViewport().setBackground(cardColor);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        card.add(scroll, BorderLayout.CENTER);

        progress.setStringPainted(true);
        progress.setForeground(new Color(0x7CFF3A));
        progress.setBackground(new Color(0x202020));
        progress.setVisible(false);
        status.setForeground(Color.WHITE);
        status.setFont(BODY);
        status.setOpaque(true);
        status.setBackground(cardColor);
        JPanel south = new JPanel();
        south.setOpaque(true);
        south.setBackground(cardColor);
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        status.setAlignmentX(CENTER_ALIGNMENT);
        progress.setAlignmentX(CENTER_ALIGNMENT);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        buttons.setOpaque(false);
        south.add(status);
        south.add(Box.createVerticalStrut(8));
        south.add(progress);
        south.add(Box.createVerticalStrut(12));
        south.add(buttons);
        card.add(south, BorderLayout.SOUTH);

        JPanel frame = (JPanel) getContentPane();
        frame.setLayout(new BorderLayout());
        frame.setBorder(BorderFactory.createEmptyBorder(28, 36, 28, 36));
        frame.add(card, BorderLayout.CENTER);
    }

    private void showSnapshot(FlowController.Snapshot current) {
        this.snapshot = current;
        ResolverLog.info("Loading state: " + LoadingState.AWAITING_USER);
        title.setText(ReportFormatter.title(current));
        body.setText(ReportFormatter.body(current));
        body.setCaretPosition(0);
        progress.setVisible(false);
        status.setText(" ");
        buttons.removeAll();
        if (current.offline() && current.choices().isEmpty()) {
            addButton("Retry", this::retry);
        } else if (!current.plan().conflicts().isEmpty() && current.plan().actionable().isEmpty()) {
            addButton("View Details", this::details);
        } else if (current.plan().dependencies().stream().anyMatch(dependency -> dependency.status() == Dependency.Status.DUPLICATE)
                && current.plan().actionable().isEmpty()) {
            addButton("Move Aside", this::repair);
        } else if (current.plan().actionable().size() > 1) {
            addButton("Install All", this::install);
            if (!current.plan().conflicts().isEmpty()) {
                addButton("View Details", this::details);
            }
        } else if (current.choices().isEmpty()) {
            addButton("View Details", this::details);
            addButton("Retry", this::retry);
        } else {
            addButton("Download Here", this::install);
        }
        addButton("Exit", () -> finish(GateResult.abort("Dependency installation was cancelled. Minecraft was not started.", 1)));
        buttons.revalidate();
        buttons.repaint();
    }

    private void install() {
        setBusy(true);
        workers.submit(() -> {
            FlowController.InstallReport report = flow.install(snapshot, new FlowController.Progress() {
                @Override
                public void status(String message) {
                    SwingUtilities.invokeLater(() -> status.setText(message));
                }

                @Override
                public void bytes(long read, long total) {
                    SwingUtilities.invokeLater(() -> {
                        progress.setVisible(true);
                        if (total <= 0) {
                            progress.setIndeterminate(true);
                            progress.setString("Downloading...");
                        } else {
                            progress.setIndeterminate(false);
                            int percent = (int) Math.min(100, (read * 100) / total);
                            progress.setValue(percent);
                            progress.setString(percent + "%");
                        }
                    });
                }
            });
            SwingUtilities.invokeLater(() -> showReport(report));
        });
    }

    private void showReport(FlowController.InstallReport report) {
        setBusy(false);
        progress.setVisible(false);
        if (report.ready()) {
            title.setText(report.installed().size() > 1 ? "Dependencies Installed" : "Dependencies Installed");
            body.setText(ReportFormatter.installed(report.installed(), report.installed().size() != 1));
            status.setText(" ");
            buttons.removeAll();
            addButton("Reload", this::reload);
            buttons.revalidate();
            return;
        }
        title.setText(report.offline() ? "Internet connection unavailable" : "Download failed");
        body.setText(report.message().isBlank() ? "The dependency could not be downloaded." : report.message());
        status.setText(" ");
        buttons.removeAll();
        addButton("Retry", this::retry);
        addButton("View Details", this::details);
        addButton("Exit", () -> finish(GateResult.abort("Dependency installation was cancelled. Minecraft was not started.", 1)));
        buttons.revalidate();
    }

    private void reload() {
        ResolverLog.info("Player pressed Reload. Re-checking the mods directory before Forge starts.");
        if (flow.readyToLaunch()) {
            ResolverLog.info("Loading state: " + LoadingState.READY_TO_LAUNCH);
            finish(GateResult.ready());
            return;
        }
        SnapshotRefresh.refresh(this, flow);
    }

    private void retry() {
        setBusy(true);
        workers.submit(() -> {
            FlowController.Snapshot fresh = flow.inspect();
            SwingUtilities.invokeLater(() -> {
                setBusy(false);
                showSnapshot(fresh);
            });
        });
    }

    private void repair() {
        setBusy(true);
        workers.submit(() -> {
            boolean ok = flow.repairDuplicates();
            SwingUtilities.invokeLater(() -> {
                setBusy(false);
                if (ok && flow.readyToLaunch()) {
                    title.setText("Dependencies Installed");
                    body.setText("Incompatible copies were moved aside and were not deleted.\n\nMinecraft is ready to reload.");
                    buttons.removeAll();
                    addButton("Reload", this::reload);
                    buttons.revalidate();
                } else {
                    showSnapshot(flow.inspect());
                }
            });
        });
    }

    private void details() {
        String extra = snapshot.detail() == null ? "" : snapshot.detail();
        body.setText(ReportFormatter.body(snapshot) + (extra.isBlank() ? "" : "\n\n" + extra)
                + "\n\nTechnical details were written to logs/dependencyresolver.log.");
    }

    private void setBusy(boolean busy) {
        buttons.setVisible(!busy);
        if (busy) {
            status.setText("Working...");
        }
    }

    private void addButton(String label, Runnable action) {
        JButton button = new JButton(label);
        button.setFont(BODY);
        button.setFocusPainted(false);
        button.setBackground(new Color(0x8B8B8B));
        button.setForeground(Color.WHITE);
        button.setOpaque(true);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.BLACK, 2),
                BorderFactory.createEmptyBorder(6, 16, 6, 16)));
        button.addActionListener(event -> action.run());
        buttons.add(button);
    }

    private void finish(GateResult gateResult) {
        if (result.isDone()) {
            return;
        }
        workers.shutdownNow();
        result.complete(gateResult);
        dispose();
    }

    private static final class SnapshotRefresh {
        private static void refresh(DependencyWindow window, FlowController flow) {
            window.showSnapshot(flow.inspect());
        }
    }

    private static final class DirtPanel extends JPanel {
        private final BufferedImage dirt = dirt();

        private DirtPanel() {
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D canvas = (Graphics2D) graphics;
            canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (int y = 0; y < getHeight(); y += dirt.getHeight()) {
                for (int x = 0; x < getWidth(); x += dirt.getWidth()) {
                    canvas.drawImage(dirt, x, y, null);
                }
            }
        }

        private static BufferedImage dirt() {
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            Random random = new Random(0xC0FFEE);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int noise = random.nextInt(30);
                    int red = 0x3C + noise;
                    int green = 0x2C + noise / 2;
                    int blue = 0x18 + noise / 3;
                    image.setRGB(x, y, (red << 16) | (green << 8) | blue);
                }
            }
            return image;
        }
    }
}
