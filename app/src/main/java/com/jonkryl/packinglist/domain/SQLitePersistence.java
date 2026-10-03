package com.jonkryl.packinglist.domain;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jonkryl.packinglist.domain.PackingModels.*;

/** Relational, private, transactional storage. No network, analytics or account dependency. */
public final class SQLitePersistence extends SQLiteOpenHelper implements PackingRepository.Persistence {
    public static final String DATABASE_NAME = "sobrano.db";

    public SQLitePersistence(Context context) {
        this(context, DATABASE_NAME);
    }

    /** Custom name is useful for isolated real-device tests; null creates an in-memory database. */
    public SQLitePersistence(Context context, String databaseName) {
        super(context.getApplicationContext(), databaseName, null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
        db.execSQL("PRAGMA synchronous=FULL");
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value INTEGER NOT NULL)");
        db.execSQL("INSERT INTO metadata(key,value) VALUES ('next_id',1)");
        db.execSQL("CREATE TABLE trips (id INTEGER PRIMARY KEY, title TEXT NOT NULL, "
                + "start_date TEXT NOT NULL, end_date TEXT NOT NULL, trip_order INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE people (id INTEGER PRIMARY KEY, trip_id INTEGER NOT NULL, "
                + "name TEXT NOT NULL, list_order INTEGER NOT NULL, UNIQUE(id,trip_id), "
                + "FOREIGN KEY(trip_id) REFERENCES trips(id) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE bags (id INTEGER PRIMARY KEY, trip_id INTEGER NOT NULL, "
                + "name TEXT NOT NULL, list_order INTEGER NOT NULL, UNIQUE(id,trip_id), "
                + "FOREIGN KEY(trip_id) REFERENCES trips(id) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY, trip_id INTEGER NOT NULL, "
                + "name TEXT NOT NULL, quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 9999), "
                + "person_id INTEGER, bag_id INTEGER, packed INTEGER NOT NULL CHECK(packed IN (0,1)), "
                + "item_order INTEGER NOT NULL, FOREIGN KEY(trip_id) REFERENCES trips(id) ON DELETE CASCADE, "
                + "FOREIGN KEY(person_id,trip_id) REFERENCES people(id,trip_id), "
                + "FOREIGN KEY(bag_id,trip_id) REFERENCES bags(id,trip_id))");
        db.execSQL("CREATE INDEX items_trip_order ON items(trip_id,item_order,id)");
        db.execSQL("CREATE INDEX people_trip_order ON people(trip_id,list_order,id)");
        db.execSQL("CREATE INDEX bags_trip_order ON bags(trip_id,list_order,id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Release 1 has one schema. Never drop a user's packing lists on an unexpected version.
        throw new IllegalStateException("Unsupported database upgrade " + oldVersion + " → " + newVersion);
    }

    @Override public synchronized State read() {
        SQLiteDatabase db = getReadableDatabase();
        Map<Long, TripBuilder> trips = new LinkedHashMap<>();
        long nextId = 1;
        // A transaction gives one consistent snapshot, including the ID allocator.
        db.beginTransaction();
        try {
            try (Cursor cursor = db.rawQuery("SELECT value FROM metadata WHERE key='next_id'", null)) {
                if (cursor.moveToFirst()) nextId = cursor.getLong(0);
            }
            try (Cursor cursor = db.rawQuery("SELECT id,title,start_date,end_date FROM trips ORDER BY trip_order,id", null)) {
                while (cursor.moveToNext()) {
                    TripBuilder trip = new TripBuilder(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3));
                    trips.put(trip.id, trip);
                }
            }
            try (Cursor cursor = db.rawQuery("SELECT id,trip_id,name FROM people ORDER BY trip_id,list_order,id", null)) {
                while (cursor.moveToNext()) {
                    TripBuilder trip = trips.get(cursor.getLong(1));
                    if (trip == null) throw new IllegalStateException("Person has no trip");
                    trip.people.add(new Person(cursor.getLong(0), cursor.getString(2)));
                }
            }
            try (Cursor cursor = db.rawQuery("SELECT id,trip_id,name FROM bags ORDER BY trip_id,list_order,id", null)) {
                while (cursor.moveToNext()) {
                    TripBuilder trip = trips.get(cursor.getLong(1));
                    if (trip == null) throw new IllegalStateException("Bag has no trip");
                    trip.bags.add(new Bag(cursor.getLong(0), cursor.getString(2)));
                }
            }
            try (Cursor cursor = db.rawQuery("SELECT id,trip_id,name,quantity,person_id,bag_id,packed,item_order "
                    + "FROM items ORDER BY trip_id,item_order,id", null)) {
                while (cursor.moveToNext()) {
                    TripBuilder trip = trips.get(cursor.getLong(1));
                    if (trip == null) throw new IllegalStateException("Item has no trip");
                    Long personId = cursor.isNull(4) ? null : cursor.getLong(4);
                    Long bagId = cursor.isNull(5) ? null : cursor.getLong(5);
                    trip.items.add(new Item(cursor.getLong(0), cursor.getString(2), cursor.getInt(3),
                            personId, bagId, cursor.getInt(6) == 1, cursor.getLong(7)));
                }
            }
            List<Trip> result = new ArrayList<>();
            for (TripBuilder trip : trips.values()) result.add(trip.build());
            db.setTransactionSuccessful();
            return new State(nextId, result);
        } finally {
            db.endTransaction();
        }
    }

    @Override public synchronized void write(State state) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // Delete dependent rows first, so composite ownership constraints are always satisfied.
            db.delete("items", null, null);
            db.delete("people", null, null);
            db.delete("bags", null, null);
            db.delete("trips", null, null);
            for (int tripIndex = 0; tripIndex < state.trips.size(); tripIndex++) {
                Trip trip = state.trips.get(tripIndex);
                ContentValues values = new ContentValues();
                values.put("id", trip.id);
                values.put("title", trip.title);
                values.put("start_date", trip.startDate);
                values.put("end_date", trip.endDate);
                values.put("trip_order", tripIndex);
                db.insertOrThrow("trips", null, values);
                for (int i = 0; i < trip.people.size(); i++) {
                    Person person = trip.people.get(i);
                    values = new ContentValues();
                    values.put("id", person.id);
                    values.put("trip_id", trip.id);
                    values.put("name", person.name);
                    values.put("list_order", i);
                    db.insertOrThrow("people", null, values);
                }
                for (int i = 0; i < trip.bags.size(); i++) {
                    Bag bag = trip.bags.get(i);
                    values = new ContentValues();
                    values.put("id", bag.id);
                    values.put("trip_id", trip.id);
                    values.put("name", bag.name);
                    values.put("list_order", i);
                    db.insertOrThrow("bags", null, values);
                }
                for (Item item : trip.items) {
                    values = new ContentValues();
                    values.put("id", item.id);
                    values.put("trip_id", trip.id);
                    values.put("name", item.name);
                    values.put("quantity", item.quantity);
                    if (item.personId == null) values.putNull("person_id"); else values.put("person_id", item.personId);
                    if (item.bagId == null) values.putNull("bag_id"); else values.put("bag_id", item.bagId);
                    values.put("packed", item.packed ? 1 : 0);
                    values.put("item_order", item.order);
                    db.insertOrThrow("items", null, values);
                }
            }
            ContentValues values = new ContentValues();
            values.put("value", state.nextId);
            int changed = db.update("metadata", values, "key=?", new String[]{"next_id"});
            if (changed != 1) throw new IllegalStateException("Missing ID allocator");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static final class TripBuilder {
        final long id;
        final String title;
        final String startDate;
        final String endDate;
        final List<Person> people = new ArrayList<>();
        final List<Bag> bags = new ArrayList<>();
        final List<Item> items = new ArrayList<>();
        TripBuilder(long id, String title, String startDate, String endDate) {
            this.id = id; this.title = title; this.startDate = startDate; this.endDate = endDate;
        }
        Trip build() { return new Trip(id, title, startDate, endDate, people, bags, items); }
    }
}
