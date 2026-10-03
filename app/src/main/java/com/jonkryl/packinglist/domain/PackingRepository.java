package com.jonkryl.packinglist.domain;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static com.jonkryl.packinglist.domain.PackingModels.*;

/**
 * Local application boundary. Each successful mutation publishes one persisted immutable state.
 * Storage failure leaves the previous state intact. Duplicate names are intentionally permitted.
 */
public final class PackingRepository {
    public interface Persistence {
        State read();
        void write(State state);
    }

    public static final class UndoToken {
        public final String kind;
        public final long tripId;
        public final long entityId;
        private final PackingRepository owner;
        private final Object before;
        private final Object after;
        private final int index;
        private final List<Long> reassignedItems;
        private boolean used;

        private UndoToken(PackingRepository owner, String kind, long tripId, long entityId,
                          Object before, Object after, int index, List<Long> reassignedItems) {
            this.owner = owner;
            this.kind = kind;
            this.tripId = tripId;
            this.entityId = entityId;
            this.before = before;
            this.after = after;
            this.index = index;
            this.reassignedItems = reassignedItems == null ? Collections.emptyList()
                    : new ArrayList<>(reassignedItems);
        }
    }

    private final Persistence persistence;
    private State state;

    public PackingRepository(Persistence persistence) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        State loaded = persistence.read();
        state = loaded == null ? State.empty() : loaded;
        long largest = 0;
        for (Trip trip : state.trips) {
            largest = Math.max(largest, trip.id);
            for (Person person : trip.people) largest = Math.max(largest, person.id);
            for (Bag bag : trip.bags) largest = Math.max(largest, bag.id);
            for (Item item : trip.items) largest = Math.max(largest, item.id);
        }
        if (state.nextId <= largest) state = new State(largest + 1, state.trips);
    }

    public synchronized List<Trip> listTrips() { return state.trips; }

    public synchronized Trip getTrip(long tripId) {
        for (Trip trip : state.trips) if (trip.id == tripId) return trip;
        return null;
    }

    public synchronized List<Item> visibleItems(long tripId, boolean remainingOnly, Grouping grouping) {
        return PackingLogic.visibleItems(requireTrip(tripId), remainingOnly, grouping);
    }

    public synchronized long createTrip(String title, String startDate, String endDate,
                                        Template template, boolean english) {
        String cleanTitle = name(title, 120);
        String start = date(startDate);
        String end = date(endDate);
        datesInOrder(start, end);
        Objects.requireNonNull(template, "template");
        long next = state.nextId;
        long tripId = next++;
        List<Person> people = new ArrayList<>();
        List<Bag> bags = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        if (template != Template.EMPTY) {
            people.add(new Person(next++, english ? "Me" : "Я"));
            bags.add(new Bag(next++, english ? "Main bag" : "Основная сумка"));
            if (template == Template.WITH_KIDS) {
                people.add(new Person(next++, english ? "Child" : "Ребёнок"));
                bags.add(new Bag(next++, english ? "Day bag" : "Сумка на день"));
            }
            List<TemplateItem> entries = templateItems(template);
            for (TemplateItem entry : entries) {
                items.add(new Item(next++, english ? entry.en : entry.ru, entry.quantity,
                        people.get(entry.person).id, bags.get(entry.bag).id, false, items.size()));
            }
        }
        List<Trip> trips = new ArrayList<>(state.trips);
        trips.add(new Trip(tripId, cleanTitle, start, end, people, bags, items));
        commit(new State(next, trips));
        return tripId;
    }

    public synchronized UndoToken updateTrip(long tripId, String title, String startDate, String endDate) {
        Trip before = requireTrip(tripId);
        String start = date(startDate);
        String end = date(endDate);
        datesInOrder(start, end);
        Trip after = new Trip(before.id, name(title, 120), start, end,
                before.people, before.bags, before.items);
        replaceTrip(after, state.nextId);
        return token("edit_trip", tripId, tripId, before, after, 0, null);
    }

    public synchronized UndoToken deleteTrip(long tripId) {
        Trip before = requireTrip(tripId);
        List<Trip> trips = new ArrayList<>(state.trips);
        int index = trips.indexOf(before);
        trips.remove(index);
        commit(new State(state.nextId, trips));
        return token("delete_trip", tripId, tripId, before, null, index, null);
    }

    public synchronized long addPerson(long tripId, String personName) {
        Trip trip = requireTrip(tripId);
        long id = state.nextId;
        List<Person> people = new ArrayList<>(trip.people);
        people.add(new Person(id, name(personName, 80)));
        replaceTrip(copy(trip, people, trip.bags, trip.items), id + 1);
        return id;
    }

    public synchronized UndoToken updatePerson(long tripId, long personId, String personName) {
        Trip trip = requireTrip(tripId);
        int index = personIndex(trip, personId);
        Person before = trip.people.get(index);
        Person after = new Person(personId, name(personName, 80));
        List<Person> people = new ArrayList<>(trip.people);
        people.set(index, after);
        replaceTrip(copy(trip, people, trip.bags, trip.items), state.nextId);
        return token("edit_person", tripId, personId, before, after, index, null);
    }

    public synchronized UndoToken deletePerson(long tripId, long personId) {
        Trip trip = requireTrip(tripId);
        int index = personIndex(trip, personId);
        Person before = trip.people.get(index);
        List<Person> people = new ArrayList<>(trip.people);
        people.remove(index);
        List<Item> items = new ArrayList<>();
        List<Long> reassigned = new ArrayList<>();
        for (Item item : trip.items) {
            if (Objects.equals(item.personId, personId)) {
                reassigned.add(item.id);
                items.add(new Item(item.id, item.name, item.quantity, null, item.bagId, item.packed, item.order));
            } else items.add(item);
        }
        replaceTrip(copy(trip, people, trip.bags, items), state.nextId);
        return token("delete_person", tripId, personId, before, null, index, reassigned);
    }

    public synchronized long addBag(long tripId, String bagName) {
        Trip trip = requireTrip(tripId);
        long id = state.nextId;
        List<Bag> bags = new ArrayList<>(trip.bags);
        bags.add(new Bag(id, name(bagName, 80)));
        replaceTrip(copy(trip, trip.people, bags, trip.items), id + 1);
        return id;
    }

    public synchronized UndoToken updateBag(long tripId, long bagId, String bagName) {
        Trip trip = requireTrip(tripId);
        int index = bagIndex(trip, bagId);
        Bag before = trip.bags.get(index);
        Bag after = new Bag(bagId, name(bagName, 80));
        List<Bag> bags = new ArrayList<>(trip.bags);
        bags.set(index, after);
        replaceTrip(copy(trip, trip.people, bags, trip.items), state.nextId);
        return token("edit_bag", tripId, bagId, before, after, index, null);
    }

    public synchronized UndoToken deleteBag(long tripId, long bagId) {
        Trip trip = requireTrip(tripId);
        int index = bagIndex(trip, bagId);
        Bag before = trip.bags.get(index);
        List<Bag> bags = new ArrayList<>(trip.bags);
        bags.remove(index);
        List<Item> items = new ArrayList<>();
        List<Long> reassigned = new ArrayList<>();
        for (Item item : trip.items) {
            if (Objects.equals(item.bagId, bagId)) {
                reassigned.add(item.id);
                items.add(new Item(item.id, item.name, item.quantity, item.personId, null, item.packed, item.order));
            } else items.add(item);
        }
        replaceTrip(copy(trip, trip.people, bags, items), state.nextId);
        return token("delete_bag", tripId, bagId, before, null, index, reassigned);
    }

    public synchronized long addItem(long tripId, String itemName, int quantity, Long personId, Long bagId) {
        Trip trip = requireTrip(tripId);
        checkReferences(trip, personId, bagId);
        checkQuantity(quantity);
        long id = state.nextId;
        // A never-reused sequence also keeps undo of the last removed item before later additions.
        long order = id;
        List<Item> items = new ArrayList<>(trip.items);
        items.add(new Item(id, name(itemName, 160), quantity, personId, bagId, false, order));
        replaceTrip(copy(trip, trip.people, trip.bags, items), id + 1);
        return id;
    }

    public synchronized UndoToken updateItem(long tripId, long itemId, String itemName, int quantity,
                                              Long personId, Long bagId) {
        Trip trip = requireTrip(tripId);
        checkReferences(trip, personId, bagId);
        checkQuantity(quantity);
        int index = itemIndex(trip, itemId);
        Item before = trip.items.get(index);
        Item after = new Item(itemId, name(itemName, 160), quantity, personId, bagId, before.packed, before.order);
        List<Item> items = new ArrayList<>(trip.items);
        items.set(index, after);
        replaceTrip(copy(trip, trip.people, trip.bags, items), state.nextId);
        return token("edit_item", tripId, itemId, before, after, index, null);
    }

    public synchronized UndoToken setPacked(long tripId, long itemId, boolean packed) {
        Trip trip = requireTrip(tripId);
        int index = itemIndex(trip, itemId);
        Item before = trip.items.get(index);
        Item after = new Item(itemId, before.name, before.quantity, before.personId, before.bagId, packed, before.order);
        List<Item> items = new ArrayList<>(trip.items);
        items.set(index, after);
        replaceTrip(copy(trip, trip.people, trip.bags, items), state.nextId);
        return token("edit_item", tripId, itemId, before, after, index, null);
    }

    public synchronized UndoToken deleteItem(long tripId, long itemId) {
        Trip trip = requireTrip(tripId);
        int index = itemIndex(trip, itemId);
        Item before = trip.items.get(index);
        List<Item> items = new ArrayList<>(trip.items);
        items.remove(index);
        replaceTrip(copy(trip, trip.people, trip.bags, items), state.nextId);
        return token("delete_item", tripId, itemId, before, null, index, null);
    }

    public synchronized long copyTrip(long tripId, String title, boolean resetPacked) {
        Trip original = requireTrip(tripId);
        long next = state.nextId;
        long newId = next++;
        Map<Long, Long> personIds = new HashMap<>();
        Map<Long, Long> bagIds = new HashMap<>();
        List<Person> people = new ArrayList<>();
        List<Bag> bags = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        for (Person person : original.people) {
            personIds.put(person.id, next);
            people.add(new Person(next++, person.name));
        }
        for (Bag bag : original.bags) {
            bagIds.put(bag.id, next);
            bags.add(new Bag(next++, bag.name));
        }
        for (Item item : original.items) {
            items.add(new Item(next++, item.name, item.quantity, personIds.get(item.personId),
                    bagIds.get(item.bagId), !resetPacked && item.packed, item.order));
        }
        List<Trip> trips = new ArrayList<>(state.trips);
        trips.add(new Trip(newId, name(title, 120), original.startDate, original.endDate, people, bags, items));
        commit(new State(next, trips));
        return newId;
    }

    public synchronized String exportTrip(long tripId, boolean english) {
        return PackingLogic.exportText(requireTrip(tripId), english);
    }

    /** Restores only this operation; later edits to other fields and entities are preserved. */
    public synchronized boolean undo(UndoToken token) {
        if (token == null || token.owner != this || token.used) return false;
        if (token.kind.equals("delete_trip")) {
            if (getTrip(token.tripId) != null) return false;
            List<Trip> trips = new ArrayList<>(state.trips);
            trips.add(Math.min(token.index, trips.size()), (Trip) token.before);
            commit(new State(state.nextId, trips));
            token.used = true;
            return true;
        }
        Trip trip = getTrip(token.tripId);
        if (trip == null) return false;
        Trip restored;
        switch (token.kind) {
            case "edit_trip": restored = undoTripEdit(trip, (Trip) token.before, (Trip) token.after); break;
            case "delete_person": restored = undoPersonDelete(trip, token); break;
            case "delete_bag": restored = undoBagDelete(trip, token); break;
            case "delete_item": restored = undoItemDelete(trip, token); break;
            case "edit_person": restored = undoPersonEdit(trip, token); break;
            case "edit_bag": restored = undoBagEdit(trip, token); break;
            case "edit_item": restored = undoItemEdit(trip, token); break;
            default: return false;
        }
        if (restored == null) return false;
        replaceTrip(restored, state.nextId);
        token.used = true;
        return true;
    }

    private Trip undoTripEdit(Trip trip, Trip before, Trip after) {
        String title = restoredValue(trip.title, before.title, after.title);
        String start = restoredValue(trip.startDate, before.startDate, after.startDate);
        String end = restoredValue(trip.endDate, before.endDate, after.endDate);
        // If an intervening date edit makes the partial restoration invalid, retain its date range.
        if (!start.isEmpty() && !end.isEmpty() && start.compareTo(end) > 0) {
            start = trip.startDate;
            end = trip.endDate;
        }
        return new Trip(trip.id, title, start, end, trip.people, trip.bags, trip.items);
    }

    private Trip undoPersonDelete(Trip trip, UndoToken token) {
        for (Person person : trip.people) if (person.id == token.entityId) return null;
        List<Person> people = new ArrayList<>(trip.people);
        people.add(Math.min(token.index, people.size()), (Person) token.before);
        List<Item> items = new ArrayList<>();
        for (Item item : trip.items) {
            Long personId = item.personId;
            if (personId == null && token.reassignedItems.contains(item.id)) {
                personId = token.entityId;
            }
            items.add(new Item(item.id, item.name, item.quantity, personId, item.bagId, item.packed, item.order));
        }
        return copy(trip, people, trip.bags, items);
    }

    private Trip undoBagDelete(Trip trip, UndoToken token) {
        for (Bag bag : trip.bags) if (bag.id == token.entityId) return null;
        List<Bag> bags = new ArrayList<>(trip.bags);
        bags.add(Math.min(token.index, bags.size()), (Bag) token.before);
        List<Item> items = new ArrayList<>();
        for (Item item : trip.items) {
            Long bagId = item.bagId;
            if (bagId == null && token.reassignedItems.contains(item.id)) {
                bagId = token.entityId;
            }
            items.add(new Item(item.id, item.name, item.quantity, item.personId, bagId, item.packed, item.order));
        }
        return copy(trip, trip.people, bags, items);
    }

    private Trip undoItemDelete(Trip trip, UndoToken token) {
        for (Item item : trip.items) if (item.id == token.entityId) return null;
        Item before = (Item) token.before;
        Long personId = hasPerson(trip, before.personId) ? before.personId : null;
        Long bagId = hasBag(trip, before.bagId) ? before.bagId : null;
        Item restored = new Item(before.id, before.name, before.quantity, personId, bagId, before.packed, before.order);
        List<Item> items = new ArrayList<>(trip.items);
        items.add(Math.min(token.index, items.size()), restored);
        return copy(trip, trip.people, trip.bags, items);
    }

    private Trip undoPersonEdit(Trip trip, UndoToken token) {
        List<Person> people = new ArrayList<>(trip.people);
        Person before = (Person) token.before;
        Person after = (Person) token.after;
        for (int i = 0; i < people.size(); i++) if (people.get(i).id == token.entityId) {
            Person current = people.get(i);
            if (!current.name.equals(after.name)) return null;
            people.set(i, before);
            return copy(trip, people, trip.bags, trip.items);
        }
        return null;
    }

    private Trip undoBagEdit(Trip trip, UndoToken token) {
        List<Bag> bags = new ArrayList<>(trip.bags);
        Bag before = (Bag) token.before;
        Bag after = (Bag) token.after;
        for (int i = 0; i < bags.size(); i++) if (bags.get(i).id == token.entityId) {
            Bag current = bags.get(i);
            if (!current.name.equals(after.name)) return null;
            bags.set(i, before);
            return copy(trip, trip.people, bags, trip.items);
        }
        return null;
    }

    private Trip undoItemEdit(Trip trip, UndoToken token) {
        Item before = (Item) token.before;
        Item after = (Item) token.after;
        List<Item> items = new ArrayList<>(trip.items);
        for (int i = 0; i < items.size(); i++) if (items.get(i).id == token.entityId) {
            Item current = items.get(i);
            String itemName = restoredValue(current.name, before.name, after.name);
            int quantity = current.quantity == after.quantity ? before.quantity : current.quantity;
            Long personId = restoredValue(current.personId, before.personId, after.personId);
            Long bagId = restoredValue(current.bagId, before.bagId, after.bagId);
            if (!hasPerson(trip, personId)) personId = null;
            if (!hasBag(trip, bagId)) bagId = null;
            boolean packed = current.packed == after.packed ? before.packed : current.packed;
            items.set(i, new Item(current.id, itemName, quantity, personId, bagId, packed, current.order));
            return copy(trip, trip.people, trip.bags, items);
        }
        return null;
    }

    private <T> T restoredValue(T current, T before, T after) {
        return Objects.equals(current, after) ? before : current;
    }

    private UndoToken token(String kind, long tripId, long entityId, Object before, Object after,
                            int index, List<Long> reassignedItems) {
        return new UndoToken(this, kind, tripId, entityId, before, after, index, reassignedItems);
    }

    private void commit(State candidate) {
        persistence.write(candidate);
        state = candidate;
    }

    private void replaceTrip(Trip replacement, long nextId) {
        List<Trip> trips = new ArrayList<>(state.trips);
        for (int i = 0; i < trips.size(); i++) if (trips.get(i).id == replacement.id) {
            trips.set(i, replacement);
            commit(new State(nextId, trips));
            return;
        }
        throw new IllegalArgumentException("Trip not found");
    }

    private static Trip copy(Trip trip, List<Person> people, List<Bag> bags, List<Item> items) {
        return new Trip(trip.id, trip.title, trip.startDate, trip.endDate, people, bags, items);
    }

    private Trip requireTrip(long id) {
        Trip trip = getTrip(id);
        if (trip == null) throw new IllegalArgumentException("Trip not found");
        return trip;
    }

    private static int personIndex(Trip trip, long id) {
        for (int i = 0; i < trip.people.size(); i++) if (trip.people.get(i).id == id) return i;
        throw new IllegalArgumentException("Person not found");
    }

    private static int bagIndex(Trip trip, long id) {
        for (int i = 0; i < trip.bags.size(); i++) if (trip.bags.get(i).id == id) return i;
        throw new IllegalArgumentException("Bag not found");
    }

    private static int itemIndex(Trip trip, long id) {
        for (int i = 0; i < trip.items.size(); i++) if (trip.items.get(i).id == id) return i;
        throw new IllegalArgumentException("Item not found");
    }

    private static boolean hasPerson(Trip trip, Long id) {
        if (id == null) return true;
        for (Person person : trip.people) if (person.id == id) return true;
        return false;
    }

    private static boolean hasBag(Trip trip, Long id) {
        if (id == null) return true;
        for (Bag bag : trip.bags) if (bag.id == id) return true;
        return false;
    }

    private static void checkReferences(Trip trip, Long personId, Long bagId) {
        if (!hasPerson(trip, personId)) throw new IllegalArgumentException("Person belongs to another trip");
        if (!hasBag(trip, bagId)) throw new IllegalArgumentException("Bag belongs to another trip");
    }

    private static void checkQuantity(int quantity) {
        if (quantity < 1 || quantity > 9999) throw new IllegalArgumentException("Quantity must be 1–9999");
    }

    private static String name(String value, int maxLength) {
        if (value == null) throw new IllegalArgumentException("Name is required");
        String clean = value.trim();
        if (clean.isEmpty() || clean.length() > maxLength) throw new IllegalArgumentException("Invalid name");
        return clean;
    }

    private static String date(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) return "";
        if (!clean.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Use yyyy-MM-dd");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setLenient(false);
        try { format.parse(clean); }
        catch (ParseException invalid) { throw new IllegalArgumentException("Invalid date", invalid); }
        return clean;
    }

    private static void datesInOrder(String start, String end) {
        if (!start.isEmpty() && !end.isEmpty() && start.compareTo(end) > 0) {
            throw new IllegalArgumentException("End date is before start date");
        }
    }

    private static final class TemplateItem {
        final String ru;
        final String en;
        final int quantity;
        final int person;
        final int bag;
        TemplateItem(String ru, String en, int quantity, int person, int bag) {
            this.ru = ru; this.en = en; this.quantity = quantity; this.person = person; this.bag = bag;
        }
    }

    private static List<TemplateItem> templateItems(Template template) {
        List<TemplateItem> items = new ArrayList<>();
        if (template == Template.WEEKEND) {
            items.add(new TemplateItem("Документ для поездки", "Travel ID", 1, 0, 0));
            items.add(new TemplateItem("Зарядка для телефона", "Phone charger", 1, 0, 0));
            items.add(new TemplateItem("Смена белья", "Underwear change", 3, 0, 0));
            items.add(new TemplateItem("Носки", "Socks", 3, 0, 0));
            items.add(new TemplateItem("Одежда для прогулки", "Walking outfit", 1, 0, 0));
            items.add(new TemplateItem("Тёплый слой", "Warm layer", 1, 0, 0));
            items.add(new TemplateItem("Одежда для сна", "Sleepwear", 1, 0, 0));
            items.add(new TemplateItem("Зубная щётка и паста", "Toothbrush and toothpaste", 1, 0, 0));
            items.add(new TemplateItem("Средства для душа", "Shower essentials", 1, 0, 0));
            items.add(new TemplateItem("Личные лекарства", "Personal medicines", 1, 0, 0));
            items.add(new TemplateItem("Бутылка для воды", "Water bottle", 1, 0, 0));
            items.add(new TemplateItem("Небольшой пакет для белья", "Laundry pouch", 1, 0, 0));
        } else if (template == Template.BUSINESS) {
            items.add(new TemplateItem("Документ для поездки", "Travel ID", 1, 0, 0));
            items.add(new TemplateItem("Ноутбук", "Laptop", 1, 0, 0));
            items.add(new TemplateItem("Зарядка для ноутбука", "Laptop charger", 1, 0, 0));
            items.add(new TemplateItem("Зарядка для телефона", "Phone charger", 1, 0, 0));
            items.add(new TemplateItem("Комплект для встречи", "Meeting outfit", 2, 0, 0));
            items.add(new TemplateItem("Удобная одежда", "Comfortable outfit", 1, 0, 0));
            items.add(new TemplateItem("Смена белья", "Underwear change", 3, 0, 0));
            items.add(new TemplateItem("Носки", "Socks", 3, 0, 0));
            items.add(new TemplateItem("Блокнот и ручка", "Notebook and pen", 1, 0, 0));
            items.add(new TemplateItem("Наушники", "Headphones", 1, 0, 0));
            items.add(new TemplateItem("Средства личной гигиены", "Toiletries", 1, 0, 0));
            items.add(new TemplateItem("Личные лекарства", "Personal medicines", 1, 0, 0));
        } else if (template == Template.WITH_KIDS) {
            items.add(new TemplateItem("Документы семьи", "Family travel documents", 1, 0, 1));
            items.add(new TemplateItem("Зарядка для телефона", "Phone charger", 1, 0, 0));
            items.add(new TemplateItem("Смена одежды", "Clothing change", 2, 0, 0));
            items.add(new TemplateItem("Смена одежды", "Clothing change", 4, 1, 0));
            items.add(new TemplateItem("Бельё и носки", "Underwear and socks", 3, 0, 0));
            items.add(new TemplateItem("Бельё и носки", "Underwear and socks", 4, 1, 0));
            items.add(new TemplateItem("Одежда для сна", "Sleepwear", 1, 1, 0));
            items.add(new TemplateItem("Тёплая кофта", "Warm sweater", 1, 1, 0));
            items.add(new TemplateItem("Любимая игрушка", "Favourite toy", 1, 1, 1));
            items.add(new TemplateItem("Перекус в дорогу", "Travel snacks", 2, 1, 1));
            items.add(new TemplateItem("Бутылка для воды", "Water bottle", 1, 1, 1));
            items.add(new TemplateItem("Влажные салфетки", "Wet wipes", 1, 1, 1));
            items.add(new TemplateItem("Средства личной гигиены", "Toiletries", 1, 0, 0));
            items.add(new TemplateItem("Личные лекарства", "Personal medicines", 1, 0, 1));
            items.add(new TemplateItem("Пакет для грязной одежды", "Dirty clothes bag", 1, 1, 0));
        }
        return items;
    }
}
