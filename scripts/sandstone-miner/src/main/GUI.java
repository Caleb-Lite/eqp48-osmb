package main;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import utils.Webhook;

public class GUI extends VBox {
  private final ComboBox<Webhook.MiningLocation> locationDropdown;
  private final CheckBox hopCheck;
  private final TextField hopTilesField;
  private final CheckBox webhookEnabled;
  private final TextField webhookUrlField;
  private final TextField intervalField;
  private final Label statusLabel;
  private final Button startButton;

  public GUI(Webhook.WebhookConfig existingConfig) {
    setPadding(new Insets(8));
    setSpacing(8);

    GridPane form = new GridPane();
    form.setHgap(8);
    form.setVgap(8);

    Label locationLabel = new Label("Mining location:");
    locationDropdown = new ComboBox<>();
    locationDropdown.getItems().addAll(Webhook.MiningLocation.values());
    locationDropdown.setCellFactory(list -> new MiningLocationCell());
    locationDropdown.setButtonCell(new MiningLocationCell());
    locationDropdown.setPrefWidth(200);
    locationDropdown.setPromptText("Select location");

    Webhook.WebhookConfig config = existingConfig != null ? existingConfig
      : new Webhook.WebhookConfig(null, 30, false, Webhook.MiningLocation.NORTH, false, 0);
    if (config.miningLocation() != null) {
      locationDropdown.getSelectionModel().select(config.miningLocation());
    }

    hopCheck = new CheckBox("Hop when player within tiles (0 = same tile):");
    hopCheck.setSelected(config.hopEnabled());
    hopTilesField = new TextField(Integer.toString(Math.max(0, config.hopRadiusTiles())));
    hopTilesField.setPromptText("0");

    webhookEnabled = new CheckBox("Enable webhook notifications");
    webhookUrlField = new TextField();
    webhookUrlField.setPromptText("Discord webhook URL");
    intervalField = new TextField();
    intervalField.setPromptText("Interval in minutes");
    intervalField.setTooltip(new Tooltip("Send periodic updates every N minutes"));

    webhookEnabled.setSelected(config.enabled());
    webhookUrlField.setText(config.webhookUrl());
    intervalField.setText(Integer.toString(config.intervalMinutes()));

    HBox hopRow = new HBox(6, hopCheck, hopTilesField);
    HBox urlRow = new HBox(6, new Label("Webhook URL:"), webhookUrlField);
    HBox intervalRow = new HBox(6, new Label("Interval (minutes):"), intervalField);
    HBox.setHgrow(webhookUrlField, Priority.ALWAYS);

    hopTilesField.setDisable(!hopCheck.isSelected());
    hopCheck.selectedProperty().addListener((obs, oldVal, selected) ->
      hopTilesField.setDisable(!Boolean.TRUE.equals(selected))
    );

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
    form.add(hopRow, 0, 1, 2, 1);
    form.add(webhookEnabled, 0, 2, 2, 1);
    form.add(urlRow, 0, 3, 2, 1);
    form.add(intervalRow, 0, 4, 2, 1);

    getChildren().addAll(form, statusLabel, startButton);
  }

  public void setOnStart(Runnable onStart) {
    startButton.setOnAction(e -> {
      if (validateInputs() && onStart != null) {
        onStart.run();
      }
    });
  }

  public Webhook.MiningLocation getSelectedLocation() {
    return locationDropdown.getSelectionModel().getSelectedItem();
  }

  public boolean isHopEnabled() {
    return hopCheck.isSelected();
  }

  public int getHopRadiusTiles() {
    return parseNonNegativeInt(hopTilesField.getText(), 0);
  }

  public Webhook.WebhookConfig buildWebhookConfig() {
    boolean enabled = webhookEnabled.isSelected();
    String url = webhookUrlField.getText() != null ? webhookUrlField.getText().trim() : "";
    int interval = parseInterval(intervalField.getText());
    Webhook.MiningLocation location = getSelectedLocation() != null ? getSelectedLocation() : Webhook.MiningLocation.NORTH;
    boolean hopEnabled = hopCheck.isSelected();
    int hopTiles = parseNonNegativeInt(hopTilesField.getText(), 0);
    return new Webhook.WebhookConfig(url, interval, enabled, location, hopEnabled, hopTiles);
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
    if (hopCheck.isSelected()) {
      String text = hopTilesField.getText();
      if (!isNonNegativeInt(text)) {
        statusLabel.setText("Hop radius must be a non-negative number.");
        return false;
      }
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

  private boolean isNonNegativeInt(String text) {
    try {
      int value = Integer.parseInt(text == null ? "" : text.trim());
      return value >= 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private int parseNonNegativeInt(String text, int fallback) {
    try {
      int value = Integer.parseInt(text == null ? "" : text.trim());
      return Math.max(0, value);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private int parseInterval(String text) {
    try {
      int value = Integer.parseInt(text == null ? "" : text.trim());
      return Math.max(1, value);
    } catch (NumberFormatException e) {
      return 30;
    }
  }

  private static class MiningLocationCell extends ListCell<Webhook.MiningLocation> {
    @Override
    protected void updateItem(Webhook.MiningLocation item, boolean empty) {
      super.updateItem(item, empty);
      if (empty || item == null) {
        setText(null);
      } else {
        setText(item.toString());
      }
    }
  }
}
