package io.github.mihirvr.prismo.ui;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * "Go to date" (1.0.1): a calendar that jumps the grid to a day or a month. Days and months that have photos or videos
 * can be clicked; the rest are greyed (which ones have items is worked out from the grid's items in memory). The title
 * switches between a month of days and a year of months. Shown in a menu pop-up, so it follows the theme like the
 * other menus. UI thread only.
 */
public final class DateJump {

    private final TimelineView grid;
    private final Set<LocalDate> days;
    private final Set<YearMonth> months = new HashSet<>();
    private final Locale locale = Locale.getDefault();
    private final ContextMenu menu = new ContextMenu();
    private final Label title = new Label();
    private final GridPane cells = new GridPane();
    /** The month on show in the day view; its year in the month view. */
    private YearMonth shown;
    private boolean monthView;

    private DateJump(TimelineView grid) {
        this.grid = grid;
        this.days = grid.daysWithItems();
        for (LocalDate day : days) {
            months.add(YearMonth.from(day));
        }
        LocalDate top = grid.dayAtTop();
        shown = YearMonth.from(top == null ? LocalDate.now() : top);

        Button previous = new Button(null, Icon.CHEVRON_LEFT.node(16));
        Button next = new Button(null, Icon.CHEVRON_RIGHT.node(16));
        for (Button arrow : new Button[] {previous, next}) {
            arrow.getStyleClass().addAll("icon-button", "date-jump-arrow");
        }
        previous.setOnAction(_ -> move(-1));
        next.setOnAction(_ -> move(1));
        Button titleButton = new Button();
        titleButton.setGraphic(title);
        titleButton.getStyleClass().add("date-jump-title");
        titleButton.setOnAction(_ -> {
            monthView = !monthView;
            draw();
        });
        Region left = new Region();
        Region right = new Region();
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox header = new HBox(previous, left, titleButton, right, next);
        header.getStyleClass().add("date-jump-header");
        cells.getStyleClass().add("date-jump-cells");
        Label hint = new Label("Greyed: no photos or videos then");
        hint.getStyleClass().add("date-jump-hint");
        VBox content = new VBox(header, cells, hint);
        content.getStyleClass().add("date-jump");

        CustomMenuItem item = new CustomMenuItem(content, false);
        item.getStyleClass().add("date-jump-item");
        menu.getItems().add(item);
        draw();
    }

    /** Opens the calendar under {@code button}, on the month of the photos at the top of {@code grid}. */
    public static void show(Node button, TimelineView grid) {
        if (!grid.isDateOrdered()) {
            MenuItem why = new MenuItem("Sort by a date (Date taken, Date added or Date modified) to jump to one");
            why.setDisable(true);
            new ContextMenu(why).show(button, Side.BOTTOM, 0, 4);
            return;
        }
        new DateJump(grid).menu.show(button, Side.BOTTOM, 0, 4);
    }

    private void move(int step) {
        shown = monthView ? shown.plusYears(step) : shown.plusMonths(step);
        draw();
    }

    private void draw() {
        cells.getChildren().clear();
        if (monthView) {
            drawMonths();
        } else {
            drawDays();
        }
    }

    private void drawDays() {
        title.setText(shown.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, locale) + " " + shown.getYear());
        DayOfWeek first = WeekFields.of(locale).getFirstDayOfWeek();
        for (int column = 0; column < 7; column++) {
            Label name = new Label(first.plus(column).getDisplayName(TextStyle.SHORT_STANDALONE, locale));
            name.getStyleClass().add("date-jump-weekday");
            cells.add(name, column, 0);
        }
        int offset = Math.floorMod(shown.atDay(1).getDayOfWeek().getValue() - first.getValue(), 7);
        for (int day = 1; day <= shown.lengthOfMonth(); day++) {
            LocalDate date = shown.atDay(day);
            Button cell = cellButton(Integer.toString(day), days.contains(date), "date-jump-day");
            cell.setOnAction(_ -> {
                menu.hide();
                grid.jumpTo(date);
            });
            int at = offset + day - 1;
            cells.add(cell, at % 7, 1 + at / 7);
        }
    }

    private void drawMonths() {
        title.setText(Integer.toString(shown.getYear()));
        for (Month month : Month.values()) {
            YearMonth chosen = YearMonth.of(shown.getYear(), month);
            Button cell = cellButton(month.getDisplayName(TextStyle.SHORT_STANDALONE, locale), months.contains(chosen),
                    "date-jump-month");
            cell.setOnAction(_ -> {
                menu.hide();
                grid.jumpTo(chosen);
            });
            int at = month.ordinal();
            cells.add(cell, at % 4, at / 4);
        }
    }

    private static Button cellButton(String text, boolean hasItems, String styleClass) {
        Button cell = new Button(text);
        cell.getStyleClass().addAll("date-jump-cell", styleClass);
        cell.setDisable(!hasItems);
        cell.setFocusTraversable(false);
        return cell;
    }
}
