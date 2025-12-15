package utils;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class Options extends VBox {
    private static final String HOP_OUT_OF_STOCK = "Out of stock";
    private static final String HOP_STOCK_IS = "Stock is";

    private final TextField itemInputField = new TextField();
    private final TextField targetAmountField = new TextField("1");
    private final Button startButton = new Button("Start");
    private final ComboBox<String> modeCombo = new ComboBox<>();
    private final ComboBox<String> hopWhenCombo = new ComboBox<>();
    private final ComboBox<String> stockComparatorCombo = new ComboBox<>();
    private final TextField stockThresholdField = new TextField("0");
    private final TextField regionIdField = new TextField();
    private final CheckBox enableHoppingCheck = new CheckBox("Enable world hopping");
    private final CheckBox openPacksCheck = new CheckBox("Open packs");

    public Options() {
        setSpacing(8);
        setPadding(new Insets(10));

        Label modeLabel = new Label("Mode:");
        Label itemLabel = new Label("Item name or ID to buy/sell:");
        Label amountLabel = new Label("Total amount:");
        Label regionLabel = new Label("Region to prioritise (optional):");
        itemInputField.setPromptText("e.g., Air rune or 556");

        VBox generalBox = new VBox(8);
        generalBox.setPadding(new Insets(8, 0, 0, 0));
        javafx.scene.layout.HBox regionRow = new javafx.scene.layout.HBox(6);
        regionRow.getChildren().addAll(regionLabel, regionIdField);
        javafx.scene.layout.HBox modeRow = new javafx.scene.layout.HBox(6);
        modeRow.getChildren().addAll(modeLabel, modeCombo);
        generalBox.getChildren().addAll(
                regionRow,
                modeRow,
                openPacksCheck,
                itemLabel, itemInputField,
                amountLabel, targetAmountField
        );

        VBox hoppingBox = new VBox(8);
        hoppingBox.setPadding(new Insets(8, 0, 0, 0));
        enableHoppingCheck.setSelected(true);
        Label hopWhenLabel = new Label("Hop when:");
        javafx.scene.layout.HBox hopWhenRow = new javafx.scene.layout.HBox(6);
        hopWhenRow.getChildren().addAll(hopWhenLabel, hopWhenCombo);

        javafx.scene.layout.HBox comparatorRow = new javafx.scene.layout.HBox(6);
        comparatorRow.getChildren().addAll(stockComparatorCombo, stockThresholdField);
        comparatorRow.setVisible(false);
        comparatorRow.setManaged(false);

        hopWhenCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            boolean showComparator = HOP_STOCK_IS.equalsIgnoreCase(newVal);
            comparatorRow.setVisible(showComparator);
            comparatorRow.setManaged(showComparator);
        });

        enableHoppingCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            boolean enabled = Boolean.TRUE.equals(newVal);
            hopWhenRow.setDisable(!enabled);
            comparatorRow.setDisable(!enabled);
            hopWhenLabel.setDisable(!enabled);
            hopWhenCombo.setDisable(!enabled);
            stockComparatorCombo.setDisable(!enabled);
            stockThresholdField.setDisable(!enabled);
        });

        hoppingBox.getChildren().addAll(enableHoppingCheck, hopWhenRow, comparatorRow);

        Tab generalTab = new Tab("General", generalBox);
        generalTab.setClosable(false);
        Tab hoppingTab = new Tab("Hopping", hoppingBox);
        hoppingTab.setClosable(false);

        TabPane tabs = new TabPane(generalTab, hoppingTab);

        VBox spacer = new VBox();
        spacer.setMinHeight(12);

        getChildren().addAll(
                tabs,
                spacer,
                startButton
        );

        startButton.setDefaultButton(true);
        startButton.setMaxWidth(Double.MAX_VALUE);

        modeCombo.setItems(FXCollections.observableArrayList("Buy", "Sell"));
        modeCombo.setValue("Buy");

        hopWhenCombo.setItems(FXCollections.observableArrayList(HOP_OUT_OF_STOCK, HOP_STOCK_IS));
        hopWhenCombo.setValue(HOP_OUT_OF_STOCK);

        stockComparatorCombo.setItems(FXCollections.observableArrayList(">", "<", ">=", "<="));
        stockComparatorCombo.setValue("<=");
        stockThresholdField.setPrefWidth(70);
        stockThresholdField.setPromptText("amount");

        openPacksCheck.setSelected(false);
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

    public String getHopWhenSelection() {
        String selection = hopWhenCombo.getValue();
        return selection == null ? HOP_OUT_OF_STOCK : selection;
    }

    public boolean isHoppingEnabled() {
        return enableHoppingCheck.isSelected();
    }

    public Integer getRegionId() {
        return parseIntOrNull(regionIdField.getText());
    }

    public boolean isOpenPacksEnabled() {
        return openPacksCheck.isSelected();
    }

    public String getStockComparatorSelection() {
        return stockComparatorCombo.getValue();
    }

    public Integer getStockThreshold() {
        return parseIntOrNull(stockThresholdField.getText());
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
