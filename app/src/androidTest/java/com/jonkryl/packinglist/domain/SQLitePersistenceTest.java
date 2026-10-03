package com.jonkryl.packinglist.domain;

import android.content.Context;
import android.database.sqlite.SQLiteConstraintException;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.jonkryl.packinglist.domain.PackingModels.*;
import static org.junit.Assert.*;

/** Runs on the real API 24/36 SQLite engine, in its own disposable database. */
@RunWith(AndroidJUnit4.class)
public final class SQLitePersistenceTest {
    private Context context;
    private String databaseName;
    private SQLitePersistence helper;
    private PackingRepository repository;

    @Before public void createIsolatedDatabase() {
        context = ApplicationProvider.getApplicationContext();
        databaseName = "packing-domain-" + UUID.randomUUID() + ".db";
        helper = new SQLitePersistence(context, databaseName);
        repository = new PackingRepository(helper);
    }

    @After public void deleteIsolatedDatabase() {
        if (helper != null) helper.close();
        if (context != null && databaseName != null) {
            assertTrue("Temporary test database must be removed", context.deleteDatabase(databaseName));
        }
    }

    @Test public void realDatabaseReopenRestoresEditedDuplicatesRelationsQuantityAndMarks() {
        long tripId = repository.createTrip("Coast", "2026-10-10", "2026-10-12", Template.EMPTY, true);
        long alex = repository.addPerson(tripId, "Alex");
        long sam = repository.addPerson(tripId, "Sam");
        long cabin = repository.addBag(tripId, "Cabin bag");
        long day = repository.addBag(tripId, "Day bag");
        long first = repository.addItem(tripId, "Bottle", 1, alex, cabin);
        long second = repository.addItem(tripId, "Bottle", 2, sam, day);
        long unassigned = repository.addItem(tripId, "Book", 1, null, null);
        repository.updateTrip(tripId, "Coast with friends", "2026-10-11", "2026-10-13");
        repository.updatePerson(tripId, alex, "Alex Rivera");
        repository.updateBag(tripId, cabin, "Cabin backpack");
        repository.updateItem(tripId, first, "Bottle", 3, alex, cabin);
        repository.setPacked(tripId, first, true);
        long deletedId = repository.addItem(tripId, "Discarded", 1, null, null);
        repository.deleteItem(tripId, deletedId);
        long firstOrder = find(repository.getTrip(tripId), first).order;

        reopen();
        Trip restored = repository.getTrip(tripId);
        assertNotNull(restored);
        assertEquals("Coast with friends", restored.title);
        assertEquals("2026-10-11", restored.startDate);
        assertEquals("2026-10-13", restored.endDate);
        assertEquals(Arrays.asList(first, second, unassigned), ids(restored.items));
        assertEquals("Alex Rivera", restored.personName(alex));
        assertEquals("Cabin backpack", restored.bagName(cabin));
        assertEquals(3, find(restored, first).quantity);
        assertTrue(find(restored, first).packed);
        assertFalse(find(restored, second).packed);
        assertEquals(Long.valueOf(alex), find(restored, first).personId);
        assertEquals(Long.valueOf(cabin), find(restored, first).bagId);
        assertNull(find(restored, unassigned).personId);
        assertNull(find(restored, unassigned).bagId);
        assertEquals(firstOrder, find(restored, first).order);
        assertTrue(repository.addItem(tripId, "New", 1, null, null) > deletedId);
    }

    @Test public void copyWithResetAndPreserveRemapsOwnershipAfterDatabaseReopen() {
        long originalId = repository.createTrip("Family weekend", "", "", Template.WITH_KIDS, true);
        Trip original = repository.getTrip(originalId);
        repository.setPacked(originalId, original.items.get(0).id, true);
        long resetId = repository.copyTrip(originalId, "Next family weekend", true);
        long keepId = repository.copyTrip(originalId, "Packed reference", false);
        reopen();

        original = repository.getTrip(originalId);
        Trip reset = repository.getTrip(resetId);
        Trip keep = repository.getTrip(keepId);
        assertEquals(1, original.packedCount());
        assertEquals(0, reset.packedCount());
        assertEquals(1, keep.packedCount());
        assertEquals(original.items.size(), reset.items.size());
        assertEquals(original.people.size(), reset.people.size());
        assertEquals(original.bags.size(), reset.bags.size());
        for (int i = 0; i < original.items.size(); i++) {
            Item source = original.items.get(i);
            Item copy = reset.items.get(i);
            assertNotEquals(source.id, copy.id);
            assertEquals(source.name, copy.name);
            assertEquals(source.quantity, copy.quantity);
            assertEquals(source.order, copy.order);
            assertNotEquals(source.personId, copy.personId);
            assertNotEquals(source.bagId, copy.bagId);
            assertEquals(original.personName(source.personId), reset.personName(copy.personId));
            assertEquals(original.bagName(source.bagId), reset.bagName(copy.bagId));
        }
        Item copied = reset.items.get(0);
        repository.updateItem(resetId, copied.id, "Copy changed", 9, null, null);
        reopen();
        assertEquals("Family travel documents", repository.getTrip(originalId).items.get(0).name);
        assertEquals("Copy changed", repository.getTrip(resetId).items.get(0).name);
    }

    @Test public void deleteAndEditUndoPersistsWithoutDiscardingLaterItemChanges() {
        long tripId = repository.createTrip("Undo journey", "", "", Template.EMPTY, true);
        long person = repository.addPerson(tripId, "Alex");
        long bag = repository.addBag(tripId, "Bag");
        long first = repository.addItem(tripId, "Coat", 1, person, bag);
        long second = repository.addItem(tripId, "Last item", 1, person, bag);
        PackingRepository.UndoToken removedLast = repository.deleteItem(tripId, second);
        long addedLater = repository.addItem(tripId, "Added later", 1, null, null);
        assertTrue(repository.undo(removedLast));
        PackingRepository.UndoToken removedPerson = repository.deletePerson(tripId, person);
        PackingRepository.UndoToken removedBag = repository.deleteBag(tripId, bag);
        repository.updateItem(tripId, first, "Warm coat", 3, null, null);
        repository.setPacked(tripId, first, true);
        assertTrue(repository.undo(removedPerson));
        assertTrue(repository.undo(removedBag));
        PackingRepository.UndoToken edit = repository.updateItem(tripId, second, "Changed name", 2, person, bag);
        repository.setPacked(tripId, second, true);
        assertTrue(repository.undo(edit));
        reopen();

        Trip restored = repository.getTrip(tripId);
        assertEquals(Arrays.asList(first, second, addedLater), ids(restored.items));
        assertEquals("Warm coat", find(restored, first).name);
        assertEquals(3, find(restored, first).quantity);
        assertTrue(find(restored, first).packed);
        assertEquals(Long.valueOf(person), find(restored, first).personId);
        assertEquals(Long.valueOf(bag), find(restored, first).bagId);
        assertEquals("Last item", find(restored, second).name);
        assertEquals(1, find(restored, second).quantity);
        assertTrue(find(restored, second).packed);
        assertNull(find(restored, addedLater).personId);
        assertNull(find(restored, addedLater).bagId);
    }

    @Test public void invalidCrossTripOwnershipRollsBackAllRowsAndIdAllocator() {
        long firstId = repository.createTrip("Saved trip", "", "", Template.EMPTY, true);
        long secondId = repository.createTrip("Other trip", "", "", Template.EMPTY, true);
        long owner = repository.addPerson(firstId, "Other owner");
        long savedItem = repository.addItem(firstId, "Saved item", 2, null, null);
        repository.setPacked(firstId, savedItem, true);
        State saved = helper.read();
        Trip second = repository.getTrip(secondId);
        List<Item> brokenItems = new ArrayList<>(second.items);
        // This bypasses domain validation deliberately, to prove the real SQL foreign key/rollback.
        brokenItems.add(new Item(saved.nextId, "Invalid owner", 1, owner, null, false, saved.nextId));
        Trip broken = new Trip(second.id, "Would replace title", second.startDate, second.endDate,
                second.people, second.bags, brokenItems);
        List<Trip> brokenTrips = new ArrayList<>(saved.trips);
        brokenTrips.set(1, broken);
        try {
            helper.write(new State(saved.nextId + 100, brokenTrips));
            fail("Composite foreign key must reject ownership from another trip");
        } catch (SQLiteConstraintException expected) { }
        reopen();

        State after = helper.read();
        assertEquals(saved.nextId, after.nextId);
        assertEquals(2, repository.listTrips().size());
        assertEquals("Saved trip", repository.getTrip(firstId).title);
        assertEquals(Arrays.asList(savedItem), ids(repository.getTrip(firstId).items));
        assertTrue(find(repository.getTrip(firstId), savedItem).packed);
        assertEquals("Other trip", repository.getTrip(secondId).title);
        assertEquals(0, repository.getTrip(secondId).items.size());
        assertEquals("Other owner", repository.getTrip(firstId).people.get(0).name);
        assertEquals(saved.nextId, repository.addItem(firstId, "After rollback", 1, null, null));
    }

    @Test public void invalidSqlQuantityCannotEraseThePreviousTrip() {
        long tripId = repository.createTrip("Reliable", "", "", Template.EMPTY, true);
        long itemId = repository.addItem(tripId, "Saved", 4, null, null);
        State saved = helper.read();
        Trip trip = repository.getTrip(tripId);
        Item invalid = new Item(itemId, "Invalid", 0, null, null, false, itemId);
        Trip candidate = new Trip(trip.id, trip.title, trip.startDate, trip.endDate,
                trip.people, trip.bags, Collections.singletonList(invalid));
        try {
            helper.write(new State(saved.nextId, Collections.singletonList(candidate)));
            fail("SQL CHECK constraint must reject zero quantity");
        } catch (SQLiteConstraintException expected) { }
        reopen();
        Item restored = repository.getTrip(tripId).items.get(0);
        assertEquals("Saved", restored.name);
        assertEquals(4, restored.quantity);
        assertEquals(itemId, restored.id);
    }

    private void reopen() {
        helper.close();
        helper = new SQLitePersistence(context, databaseName);
        repository = new PackingRepository(helper);
    }

    private static Item find(Trip trip, long itemId) {
        for (Item item : trip.items) if (item.id == itemId) return item;
        throw new AssertionError("Missing item " + itemId);
    }

    private static List<Long> ids(List<Item> items) {
        List<Long> result = new ArrayList<>();
        for (Item item : items) result.add(item.id);
        return result;
    }
}
