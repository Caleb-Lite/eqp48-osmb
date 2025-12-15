package utils;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class Options extends VBox {
    private final TextField itemInputField = new TextField();
    private final TextField targetAmountField = new TextField("1");
    private final Button startButton = new Button("Start");
    private final ComboBox<String> modeCombo = new ComboBox<>();

    public Options() {
        setSpacing(8);
        setPadding(new Insets(10));

        Label modeLabel = new Label("Mode:");
        Label itemLabel = new Label("Item name or ID to buy/sell:");
        Label amountLabel = new Label("Total amount:");

        itemInputField.setPromptText("e.g., Air rune or 556");

        getChildren().addAll(
                modeLabel, modeCombo,
                itemLabel, itemInputField,
                amountLabel, targetAmountField,
                startButton
        );

        startButton.setDefaultButton(true);
        startButton.setMaxWidth(Double.MAX_VALUE);

        modeCombo.setItems(FXCollections.observableArrayList("Buy", "Sell"));
        modeCombo.setValue("Buy");
    }

    public String getItemInput() {
        return itemInputField.getText();
    }

    public int getTargetAmount() {
        Integer amt = parseIntOrNull(targetAmountField.getText());
        return amt == null ? 0 : Math.max(0, amt);
    }

    public String getMode() {
        String mode = modeCombo.getValue();
        return mode == null ? "Buy" : mode;
    }

    public void setOnStart(Runnable onStart) {
        startButton.setOnAction(event -> {
            if (onStart != null) {
                onStart.run();
            }
        });
    }

    public void closeWindow() {
        if (getScene() != null && getScene().getWindow() != null) {
            getScene().getWindow().hide();
        }
    }

    private Integer parseIntOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
