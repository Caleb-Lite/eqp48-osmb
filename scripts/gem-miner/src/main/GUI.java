package main;

import data.Locations;
import javafx.application.Platform;
import data.Locations.MiningLocation;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.control.ListCell;
import javafx.stage.Stage;
import utils.Webhook;

public class GUI extends VBox {
    private final ComboBox<MiningLocation> locationDropdown;
    private final CheckBox webhookEnabled;
    private final TextField webhookUrlField;
    private final TextField intervalField;
    private final Label statusLabel;
    private final Button startButton;

    public GUI(MiningLocation defaultLocation, Webhook.WebhookConfig existingConfig) {
        setPadding(new Insets(8));
        setSpacing(8);

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);

        Label locationLabel = new Label("Location:");
        locationDropdown = new ComboBox<>();
        locationDropdown.getItems().addAll(Locations.UPPER, Locations.UNDERGROUND);
        locationDropdown.setCellFactory(list -> new MiningLocationCell());
        locationDropdown.setButtonCell(new MiningLocationCell());
        locationDropdown.setPrefWidth(260);
        locationDropdown.setPromptText("Select a location");
        if (defaultLocation != null) {
            locationDropdown.getSelectionModel().select(defaultLocation);
        }

        webhookEnabled = new CheckBox("Enable webhook notifications");
        webhookUrlField = new TextField();
        webhookUrlField.setPromptText("Discord webhook URL");
        intervalField = new TextField();
        intervalField.setPromptText("Interval in minutes");
        intervalField.setTooltip(new Tooltip("Send periodic updates every N minutes"));

        Webhook.WebhookConfig config = existingConfig != null ? existingConfig : new Webhook.WebhookConfig(null, 30, false);
        webhookEnabled.setSelected(config.enabled());
        webhookUrlField.setText(config.webhookUrl());
        intervalField.setText(Integer.toString(config.intervalMinutes()));

        HBox urlRow = new HBox(6, new Label("Webhook URL:"), webhookUrlField);
        HBox intervalRow = new HBox(6, new Label("Interval (minutes):"), intervalField);
        HBox.setHgrow(webhookUrlField, Priority.ALWAYS);

        webhookUrlField.setDisable(!webhookEnabled.isSelected());
        intervalField.setDisable(!webhookEnabled.isSelected());
        webhookEnabled.selectedProperty().addListener((obs, oldVal, selected) -> {
            boolean enabled = Boolean.TRUE.equals(selected);
            webhookUrlField.setDisable(!enabled);
            intervalField.setDisable(!enabled);
        });

        statusLabel = new Label("");
        startButton = new Button("Start");
        startButton.setDefaultButton(true);

        form.add(locationLabel, 0, 0);
        form.add(locationDropdown, 1, 0);
        form.add(webhookEnabled, 0, 1, 2, 1);
        form.add(urlRow, 0, 2, 2, 1);
        form.add(intervalRow, 0, 3, 2, 1);

        getChildren().addAll(form, statusLabel, startButton);
    }

    public void setOnStart(Runnable onStart) {
        startButton.setOnAction(e -> {
            if (validateInputs() && onStart != null) {
                onStart.run();
            }
        });
    }

    public MiningLocation getSelectedLocation() {
        return locationDropdown.getSelectionModel().getSelectedItem();
    }

    public Webhook.WebhookConfig buildWebhookConfig() {
        boolean enabled = webhookEnabled.isSelected();
        String url = webhookUrlField.getText() != null ? webhookUrlField.getText().trim() : "";
        int interval = parseInterval(intervalField.getText());
        return new Webhook.WebhookConfig(url, interval, enabled);
    }

    public void closeWindow() {
        if (getScene() == null || getScene().getWindow() == null) {
            return;
        }
        Runnable closer = () -> {
            if (getScene() == null || getScene().getWindow() == null) {
                return;
            }
            if (getScene().getWindow() instanceof Stage stage) {
                stage.close();
            } else {
                getScene().getWindow().hide();
            }
        };
        if (Platform.isFxApplicationThread()) {
            closer.run();
        } else {
            Platform.runLater(closer);
        }
    }

    private boolean validateInputs() {
        if (getSelectedLocation() == null) {
            statusLabel.setText("Please select a mining location.");
            return false;
        }
        if (webhookEnabled.isSelected()) {
            int interval = parseInterval(intervalField.getText());
            if (interval <= 0) {
                statusLabel.setText("Interval must be at least 1 minute.");
                return false;
            }
            String url = webhookUrlField.getText();
            if (url == null || url.isBlank()) {
                statusLabel.setText("Please enter a webhook URL or disable webhook notifications.");
                return false;
            }
        }
        statusLabel.setText("");
        return true;
    }

    private int parseInterval(String text) {
        try {
            int value = Integer.parseInt(text == null ? "" : text.trim());
            return Math.max(1, value);
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    private static class MiningLocationCell extends ListCell<MiningLocation> {
        @Override
        protected void updateItem(MiningLocation item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
            } else {
                setText(item.displayName());
            }
        }
    }
}
