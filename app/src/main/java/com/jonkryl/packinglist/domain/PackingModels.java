package com.jonkryl.packinglist.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable values shared by the screen, domain and storage. Dates use yyyy-MM-dd or an empty string. */
public final class PackingModels {
    private PackingModels() {}

    public enum Template { EMPTY, WEEKEND, BUSINESS, WITH_KIDS }
    public enum Grouping { NONE, PERSON, BAG }

    public static final class State {
        public final long nextId;
        public final List<Trip> trips;

        public State(long nextId, List<Trip> trips) {
            this.nextId = nextId;
            this.trips = immutable(trips);
        }

        public static State empty() { return new State(1, Collections.emptyList()); }
    }

    public static final class Trip {
        public final long id;
        public final String title;
        public final String startDate;
        public final String endDate;
        public final List<Person> people;
        public final List<Bag> bags;
        public final List<Item> items;

        public Trip(long id, String title, String startDate, String endDate,
                    List<Person> people, List<Bag> bags, List<Item> items) {
            this.id = id;
            this.title = title;
            this.startDate = startDate;
            this.endDate = endDate;
            this.people = immutable(people);
            this.bags = immutable(bags);
            this.items = immutable(items);
        }

        public int packedCount() {
            int count = 0;
            for (Item item : items) if (item.packed) count++;
            return count;
        }

        public int itemCount() { return items.size(); }
        public int remainingCount() { return itemCount() - packedCount(); }

        public String personName(Long personId) {
            if (personId == null) return "";
            for (Person person : people) if (person.id == personId) return person.name;
            return "";
        }

        public String bagName(Long bagId) {
            if (bagId == null) return "";
            for (Bag bag : bags) if (bag.id == bagId) return bag.name;
            return "";
        }
    }

    public static final class Person {
        public final long id;
        public final String name;
        public Person(long id, String name) { this.id = id; this.name = name; }
    }

    public static final class Bag {
        public final long id;
        public final String name;
        public Bag(long id, String name) { this.id = id; this.name = name; }
    }

    public static final class Item {
        public final long id;
        public final String name;
        public final int quantity;
        public final Long personId;
        public final Long bagId;
        public final boolean packed;
        public final long order;

        public Item(long id, String name, int quantity, Long personId, Long bagId,
                    boolean packed, long order) {
            this.id = id;
            this.name = name;
            this.quantity = quantity;
            this.personId = personId;
            this.bagId = bagId;
            this.packed = packed;
            this.order = order;
        }
    }

    public static final class ItemGroup {
        public final String label;
        public final Long ownerId;
        public final List<Item> items;
        public ItemGroup(String label, Long ownerId, List<Item> items) {
            this.label = label;
            this.ownerId = ownerId;
            this.items = immutable(items);
        }
    }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
