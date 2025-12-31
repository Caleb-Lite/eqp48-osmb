package main;

import data.Locations;
import javafx.application.Platform;
import data.Locations.FarmerLocation;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class GUI extends VBox {

    private final ComboBox<FarmerLocation> locationDropdown;
    private final TextField foodIdField;
    private final Label statusLabel;
    private final Button startButton;
    private Integer parsedFoodId = null;

    public GUI() {
        setPadding(new Insets(8));
        setSpacing(8);

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);

        Label locationLabel = new Label("Location:");

        locationDropdown = new ComboBox<>();
        locationDropdown.setPromptText("Select a location");
        locationDropdown.getItems().addAll(Locations.allLocations());
        locationDropdown.setCellFactory(list -> new FarmerLocationCell());
        locationDropdown.setButtonCell(new FarmerLocationCell());
        locationDropdown.setPrefWidth(260);

        Label foodIdLabel = new Label("Food item ID:");

        foodIdField = new TextField();
        foodIdField.setPromptText("e.g., 379");
        HBox.setHgrow(foodIdField, Priority.ALWAYS);

        statusLabel = new Label("");

        form.add(locationLabel, 0, 0);
        form.add(locationDropdown, 1, 0);
        form.add(foodIdLabel, 0, 1);
        form.add(foodIdField, 1, 1);

        startButton = new Button("Start");
        startButton.setDefaultButton(true);
        startButton.setOnAction(e -> validateInputs());

        HBox buttons = new HBox(startButton);
        buttons.setSpacing(8);
        buttons.setAlignment(Pos.CENTER);

        getChildren().addAll(form, statusLabel, buttons);
    }

    public void setOnStart(Runnable onStart) {
        startButton.setOnAction(e -> {
            if (validateInputs() && onStart != null) {
                onStart.run();
            }
        });
    }

    public FarmerLocation getSelectedLocation() {
        return locationDropdown.getSelectionModel().getSelectedItem();
    }

    public Integer getFoodItemId() {
        return parsedFoodId;
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
        boolean locationValid = validateLocationSelection();
        boolean foodValid = validateFoodId();
        if (locationValid && foodValid) {
            String locationSummary = locationDropdown.getSelectionModel().getSelectedItem().name();
            statusLabel.setText("Location: " + locationSummary + " | Food ID: " + foodSummary());
        }
        return locationValid && foodValid;
    }

    private boolean validateLocationSelection() {
        if (locationDropdown.getSelectionModel().getSelectedItem() != null) {
            return true;
        }
        statusLabel.setText("Please select a location before starting.");
        return false;
    }

    private boolean validateFoodId() {
        parsedFoodId = null;
        String raw = foodIdField.getText();
        if (raw == null || raw.isBlank()) {
            return true;
        }

        try {
            parsedFoodId = Integer.parseInt(raw.trim());
            return true;
        } catch (NumberFormatException ex) {
            statusLabel.setText("Invalid food ID: " + raw.trim());
            parsedFoodId = null;
            return false;
        }
    }

    private String foodSummary() {
        if (parsedFoodId == null) return "none";
        return parsedFoodId.toString();
    }

    private static class FarmerLocationCell extends javafx.scene.control.ListCell<FarmerLocation> {
        @Override
        protected void updateItem(FarmerLocation item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
            } else {
                setText(item.name());
            }
        }
    }
}
