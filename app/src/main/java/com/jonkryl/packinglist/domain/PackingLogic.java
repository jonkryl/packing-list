package com.jonkryl.packinglist.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.jonkryl.packinglist.domain.PackingModels.*;

/** Pure presentation logic. Completing an item never changes its identity or stored order. */
public final class PackingLogic {
    private PackingLogic() {}

    public static List<Item> visibleItems(Trip trip, boolean remainingOnly, Grouping grouping) {
        return visibleItems(trip, remainingOnly, grouping, "");
    }

    /** Searches only the current view; matching never changes the saved packing list. */
    public static List<Item> visibleItems(Trip trip, boolean remainingOnly, Grouping grouping,
                                          String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String[] terms = normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
        List<Item> result = new ArrayList<>();
        for (Item item : trip.items) {
            if ((!remainingOnly || !item.packed) && matches(trip, item, terms)) result.add(item);
        }
        result.sort(Comparator.comparingLong(item -> item.order));
        if (grouping == Grouping.PERSON) {
            result.sort(Comparator.comparingInt(item -> personRank(trip, item.personId)));
        } else if (grouping == Grouping.BAG) {
            result.sort(Comparator.comparingInt(item -> bagRank(trip, item.bagId)));
        }
        return result;
    }

    public static List<ItemGroup> groups(Trip trip, boolean remainingOnly, Grouping grouping,
                                         boolean english) {
        return groups(trip, remainingOnly, grouping, english, "");
    }

    public static List<ItemGroup> groups(Trip trip, boolean remainingOnly, Grouping grouping,
                                         boolean english, String query) {
        List<Item> visible = visibleItems(trip, remainingOnly, grouping, query);
        List<ItemGroup> result = new ArrayList<>();
        if (grouping == Grouping.NONE) {
            if (!visible.isEmpty()) result.add(new ItemGroup("", null, visible));
            return result;
        }
        Long owner = null;
        List<Item> current = new ArrayList<>();
        for (Item item : visible) {
            Long itemOwner = grouping == Grouping.PERSON ? item.personId : item.bagId;
            if (!current.isEmpty() && !Objects.equals(owner, itemOwner)) {
                result.add(new ItemGroup(groupLabel(trip, owner, grouping, english), owner, current));
                current = new ArrayList<>();
            }
            owner = itemOwner;
            current.add(item);
        }
        if (!current.isEmpty()) {
            result.add(new ItemGroup(groupLabel(trip, owner, grouping, english), owner, current));
        }
        return result;
    }

    private static boolean matches(Trip trip, Item item, String[] terms) {
        if (terms.length == 0) return true;
        String searchable = (item.name + " " + trip.personName(item.personId) + " "
                + trip.bagName(item.bagId)).toLowerCase(Locale.ROOT);
        for (String term : terms) if (!searchable.contains(term)) return false;
        return true;
    }

    public static String exportText(Trip trip, boolean english) {
        StringBuilder text = new StringBuilder(trip.title).append('\n');
        if (!trip.startDate.isEmpty() || !trip.endDate.isEmpty()) {
            text.append(trip.startDate);
            if (!trip.endDate.isEmpty()) text.append(" — ").append(trip.endDate);
            text.append('\n');
        }
        text.append(english ? "Packed: " : "Собрано: ")
                .append(trip.packedCount()).append('/').append(trip.itemCount()).append('\n');
        for (Item item : visibleItems(trip, false, Grouping.NONE)) {
            text.append(item.packed ? "[✓] " : "[ ] ").append(item.name)
                    .append(" × ").append(item.quantity);
            if (item.personId != null) text.append(" · ").append(trip.personName(item.personId));
            if (item.bagId != null) text.append(" · ").append(trip.bagName(item.bagId));
            text.append('\n');
        }
        return text.toString();
    }

    private static String groupLabel(Trip trip, Long id, Grouping grouping, boolean english) {
        if (grouping == Grouping.PERSON) {
            return id == null ? (english ? "Unassigned" : "Без участника") : trip.personName(id);
        }
        return id == null ? (english ? "No bag" : "Без сумки") : trip.bagName(id);
    }

    private static int personRank(Trip trip, Long id) {
        if (id != null) for (int i = 0; i < trip.people.size(); i++) {
            if (trip.people.get(i).id == id) return i;
        }
        return trip.people.size();
    }

    private static int bagRank(Trip trip, Long id) {
        if (id != null) for (int i = 0; i < trip.bags.size(); i++) {
            if (trip.bags.get(i).id == id) return i;
        }
        return trip.bags.size();
    }
}
