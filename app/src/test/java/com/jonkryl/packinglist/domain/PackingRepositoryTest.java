package com.jonkryl.packinglist.domain;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.jonkryl.packinglist.domain.PackingModels.*;
import static org.junit.Assert.*;

/** Behavioral tests for user-visible state transitions, using the exact production domain. */
public class PackingRepositoryTest {
    private MemoryPersistence storage;
    private PackingRepository repository;
    private long tripId;

    @Before public void setUp() {
        storage = new MemoryPersistence();
        repository = new PackingRepository(storage);
        tripId = repository.createTrip("Coast", "2026-10-10", "2026-10-12", Template.EMPTY, true);
    }

    @Test public void duplicateNamesStayIndependentForPeopleAndBags() {
        long alex = repository.addPerson(tripId, "Alex");
        long sam = repository.addPerson(tripId, "Sam");
        long backpack = repository.addBag(tripId, "Backpack");
        long suitcase = repository.addBag(tripId, "Suitcase");
        long first = repository.addItem(tripId, "Socks", 2, alex, backpack);
        long second = repository.addItem(tripId, "Socks", 3, sam, backpack);
        long third = repository.addItem(tripId, "Socks", 4, alex, suitcase);
        long fourth = repository.addItem(tripId, "Socks", 1, alex, backpack);
        repository.setPacked(tripId, second, true);

        Trip trip = repository.getTrip(tripId);
        assertEquals(4, trip.items.size());
        assertEquals(Arrays.asList(first, second, third, fourth), ids(trip.items));
        assertFalse(find(trip, first).packed);
        assertTrue(find(trip, second).packed);
        assertFalse(find(trip, third).packed);
        assertEquals(2, find(trip, first).quantity);
        assertEquals(3, find(trip, second).quantity);
    }

    @Test public void restartRestoresEveryFieldAndNeverReusesDeletedIds() {
        long person = repository.addPerson(tripId, "Нина");
        long bag = repository.addBag(tripId, "Рюкзак");
        long item = repository.addItem(tripId, "Носки", 7, person, bag);
        repository.setPacked(tripId, item, true);
        long discarded = repository.addItem(tripId, "Spare", 1, null, null);
        repository.deleteItem(tripId, discarded);
        PackingRepository restarted = new PackingRepository(storage);

        Trip trip = restarted.getTrip(tripId);
        assertEquals("Coast", trip.title);
        assertEquals("2026-10-10", trip.startDate);
        assertEquals("2026-10-12", trip.endDate);
        assertEquals("Нина", trip.personName(person));
        assertEquals("Рюкзак", trip.bagName(bag));
        Item restored = find(trip, item);
        assertEquals(7, restored.quantity);
        assertEquals(Long.valueOf(person), restored.personId);
        assertEquals(Long.valueOf(bag), restored.bagId);
        assertTrue(restored.packed);
        assertEquals(find(repository.getTrip(tripId), item).order, restored.order);
        assertTrue(restarted.addItem(tripId, "New", 1, null, null) > discarded);
    }

    @Test public void quantityIsEditableAndInvalidQuantityCannotWrite() {
        long item = repository.addItem(tripId, "T-shirt", 2, null, null);
        repository.updateItem(tripId, item, "T-shirt", 9, null, null);
        assertEquals(9, find(repository.getTrip(tripId), item).quantity);
        int writes = storage.writes;
        expectIllegal(() -> repository.updateItem(tripId, item, "T-shirt", 0, null, null));
        expectIllegal(() -> repository.addItem(tripId, "T-shirt", 10000, null, null));
        assertEquals(writes, storage.writes);
        assertEquals(1, repository.getTrip(tripId).items.size());
        assertEquals(9, find(repository.getTrip(tripId), item).quantity);
    }

    @Test public void packedFilterKeepsStableIdsAndOrderForEveryOtherItem() {
        long first = repository.addItem(tripId, "A", 1, null, null);
        long second = repository.addItem(tripId, "B", 1, null, null);
        long third = repository.addItem(tripId, "C", 1, null, null);
        long firstOrder = find(repository.getTrip(tripId), first).order;
        long secondOrder = find(repository.getTrip(tripId), second).order;
        repository.setPacked(tripId, second, true);
        assertEquals(Arrays.asList(first, second, third), ids(repository.visibleItems(tripId, false, Grouping.NONE)));
        assertEquals(Arrays.asList(first, third), ids(repository.visibleItems(tripId, true, Grouping.NONE)));
        assertEquals(firstOrder, find(repository.getTrip(tripId), first).order);
        assertEquals(secondOrder, find(repository.getTrip(tripId), second).order);
        repository.setPacked(tripId, second, false);
        assertEquals(Arrays.asList(first, second, third), ids(repository.visibleItems(tripId, true, Grouping.NONE)));
    }

    @Test public void groupingUsesPeopleAndBagsOrderWithUnassignedLast() {
        long firstPerson = repository.addPerson(tripId, "Zoe");
        long secondPerson = repository.addPerson(tripId, "Amy");
        long firstBag = repository.addBag(tripId, "Blue");
        long secondBag = repository.addBag(tripId, "Red");
        long unassigned = repository.addItem(tripId, "A", 1, null, null);
        long second = repository.addItem(tripId, "B", 1, secondPerson, firstBag);
        long first = repository.addItem(tripId, "C", 1, firstPerson, secondBag);
        long firstAgain = repository.addItem(tripId, "D", 1, firstPerson, firstBag);
        Trip trip = repository.getTrip(tripId);
        assertEquals(Arrays.asList(first, firstAgain, second, unassigned), ids(PackingLogic.visibleItems(trip, false, Grouping.PERSON)));
        assertEquals(Arrays.asList(second, firstAgain, first, unassigned), ids(PackingLogic.visibleItems(trip, false, Grouping.BAG)));
        List<ItemGroup> groups = PackingLogic.groups(trip, false, Grouping.PERSON, true);
        assertEquals(3, groups.size());
        assertEquals("Zoe", groups.get(0).label);
        assertEquals(Arrays.asList(first, firstAgain), ids(groups.get(0).items));
        assertEquals("Unassigned", groups.get(2).label);
    }

    @Test public void itemDeleteUndoKeepsLaterAdditionsAndOriginalOrder() {
        long first = repository.addItem(tripId, "First", 1, null, null);
        long last = repository.addItem(tripId, "Last", 2, null, null);
        PackingRepository.UndoToken undo = repository.deleteItem(tripId, last);
        long addedAfter = repository.addItem(tripId, "After", 3, null, null);
        repository.setPacked(tripId, first, true);
        assertTrue(repository.undo(undo));
        Trip trip = repository.getTrip(tripId);
        assertEquals(Arrays.asList(first, last, addedAfter), ids(PackingLogic.visibleItems(trip, false, Grouping.NONE)));
        assertTrue(find(trip, first).packed);
        assertTrue(find(trip, last).order < find(trip, addedAfter).order);
        assertFalse(repository.undo(undo));
    }

    @Test public void personDeleteReassignsAndUndoPreservesLaterAssignments() {
        long alice = repository.addPerson(tripId, "Alice");
        long bob = repository.addPerson(tripId, "Bob");
        long first = repository.addItem(tripId, "Coat", 1, alice, null);
        long second = repository.addItem(tripId, "Coat", 1, alice, null);
        PackingRepository.UndoToken undo = repository.deletePerson(tripId, alice);
        assertNull(find(repository.getTrip(tripId), first).personId);
        repository.updateItem(tripId, second, "Coat", 4, bob, null);
        repository.setPacked(tripId, first, true);
        assertTrue(repository.undo(undo));
        Trip trip = repository.getTrip(tripId);
        assertEquals(Long.valueOf(alice), find(trip, first).personId);
        assertEquals(Long.valueOf(bob), find(trip, second).personId);
        assertTrue(find(trip, first).packed);
        assertEquals(4, find(trip, second).quantity);
        assertEquals("Alice", trip.people.get(0).name);
    }

    @Test public void bagDeleteReassignsAndUndoPreservesEditedItem() {
        long bag = repository.addBag(tripId, "Red bag");
        long item = repository.addItem(tripId, "Cable", 1, null, bag);
        PackingRepository.UndoToken undo = repository.deleteBag(tripId, bag);
        assertNull(find(repository.getTrip(tripId), item).bagId);
        repository.updateItem(tripId, item, "USB cable", 2, null, null);
        assertTrue(repository.undo(undo));
        Item restored = find(repository.getTrip(tripId), item);
        assertEquals(Long.valueOf(bag), restored.bagId);
        assertEquals("USB cable", restored.name);
        assertEquals(2, restored.quantity);
    }

    @Test public void itemEditUndoRestoresOnlyFieldsNotChangedAgain() {
        long item = repository.addItem(tripId, "Cable", 1, null, null);
        PackingRepository.UndoToken undo = repository.updateItem(tripId, item, "USB cable", 2, null, null);
        repository.updateItem(tripId, item, "USB cable", 8, null, null);
        repository.setPacked(tripId, item, true);
        assertTrue(repository.undo(undo));
        Item restored = find(repository.getTrip(tripId), item);
        assertEquals("Cable", restored.name);
        assertEquals(8, restored.quantity);
        assertTrue(restored.packed);
    }

    @Test public void tripEditAndDeletionCanBeUndoneWithoutReplacingOtherTrips() {
        PackingRepository.UndoToken edit = repository.updateTrip(tripId, "City", "2026-10-11", "2026-10-13");
        long item = repository.addItem(tripId, "Book", 1, null, null);
        assertTrue(repository.undo(edit));
        assertEquals("Coast", repository.getTrip(tripId).title);
        assertEquals("2026-10-10", repository.getTrip(tripId).startDate);
        assertEquals(item, repository.getTrip(tripId).items.get(0).id);
        PackingRepository.UndoToken deletion = repository.deleteTrip(tripId);
        long other = repository.createTrip("Other", "", "", Template.EMPTY, true);
        assertTrue(repository.undo(deletion));
        assertEquals(Arrays.asList(tripId, other), tripIds(repository.listTrips()));
        assertEquals("Book", repository.getTrip(tripId).items.get(0).name);
    }

    @Test public void renamePersonAndBagUndoDoesNotChangeItems() {
        long person = repository.addPerson(tripId, "A");
        long bag = repository.addBag(tripId, "B");
        long item = repository.addItem(tripId, "Scarf", 1, person, bag);
        PackingRepository.UndoToken personEdit = repository.updatePerson(tripId, person, "Alice");
        PackingRepository.UndoToken bagEdit = repository.updateBag(tripId, bag, "Backpack");
        repository.setPacked(tripId, item, true);
        assertTrue(repository.undo(personEdit));
        assertTrue(repository.undo(bagEdit));
        Trip trip = repository.getTrip(tripId);
        assertEquals("A", trip.personName(person));
        assertEquals("B", trip.bagName(bag));
        assertTrue(find(trip, item).packed);
        assertEquals(Long.valueOf(person), find(trip, item).personId);
    }

    @Test public void deletedItemUndoHandlesPersonRemovedInTheMeantime() {
        long person = repository.addPerson(tripId, "Alice");
        long bag = repository.addBag(tripId, "Bag");
        long item = repository.addItem(tripId, "Hat", 1, person, bag);
        PackingRepository.UndoToken deletion = repository.deleteItem(tripId, item);
        repository.deletePerson(tripId, person);
        repository.deleteBag(tripId, bag);
        assertTrue(repository.undo(deletion));
        assertNull(find(repository.getTrip(tripId), item).personId);
        assertNull(find(repository.getTrip(tripId), item).bagId);
    }

    @Test public void copyingTripRemapsAllIdsAndCanResetOrPreservePacked() {
        long person = repository.addPerson(tripId, "Alex");
        long bag = repository.addBag(tripId, "Bag");
        long item = repository.addItem(tripId, "Shirt", 2, person, bag);
        repository.addItem(tripId, "Unassigned", 1, null, null);
        repository.setPacked(tripId, item, true);
        long resetId = repository.copyTrip(tripId, "Next trip", true);
        long preservedId = repository.copyTrip(tripId, "Checklist", false);
        Trip original = repository.getTrip(tripId);
        Trip reset = repository.getTrip(resetId);
        Trip preserved = repository.getTrip(preservedId);
        assertEquals(0, reset.packedCount());
        assertEquals(1, preserved.packedCount());
        assertNotEquals(original.id, reset.id);
        assertNotEquals(person, reset.people.get(0).id);
        assertNotEquals(bag, reset.bags.get(0).id);
        assertNotEquals(item, reset.items.get(0).id);
        assertEquals(Long.valueOf(reset.people.get(0).id), reset.items.get(0).personId);
        assertEquals(Long.valueOf(reset.bags.get(0).id), reset.items.get(0).bagId);
        assertNull(reset.items.get(1).personId);
        assertNull(reset.items.get(1).bagId);
        assertEquals(original.startDate, reset.startDate);
        assertEquals(original.items.get(0).order, reset.items.get(0).order);
        repository.updateItem(resetId, reset.items.get(0).id, "Different", 6, null, null);
        assertEquals("Shirt", repository.getTrip(tripId).items.get(0).name);
    }

    @Test public void templatesAreIndependentEditableListsInBothLanguages() {
        long ru = repository.createTrip("С детьми", "", "", Template.WITH_KIDS, false);
        long en = repository.createTrip("With children", "", "", Template.WITH_KIDS, true);
        Trip russian = repository.getTrip(ru);
        Trip english = repository.getTrip(en);
        assertEquals(2, russian.people.size());
        assertEquals(2, russian.bags.size());
        assertTrue(russian.items.size() >= 10);
        assertEquals("Документы семьи", russian.items.get(0).name);
        assertEquals("Family travel documents", english.items.get(0).name);
        long duplicateCount = russian.items.stream().filter(item -> item.name.equals("Смена одежды")).count();
        assertEquals(2, duplicateCount);
        Item first = russian.items.get(0);
        repository.updateItem(ru, first.id, "Мои документы", 3, null, null);
        repository.deleteItem(ru, russian.items.get(1).id);
        assertEquals("Мои документы", repository.getTrip(ru).items.get(0).name);
        assertEquals("Family travel documents", repository.getTrip(en).items.get(0).name);
        assertEquals(english.items.size() - 1, repository.getTrip(ru).items.size());
        long weekend = repository.createTrip("Weekend", "", "", Template.WEEKEND, true);
        long business = repository.createTrip("Business", "", "", Template.BUSINESS, true);
        assertTrue(repository.getTrip(weekend).itemCount() >= 10);
        assertTrue(repository.getTrip(business).items.stream().anyMatch(item -> item.name.equals("Laptop")));
    }

    @Test public void exportContainsDateQuantityOwnersBagAndCheckmark() {
        long person = repository.addPerson(tripId, "Alex");
        long bag = repository.addBag(tripId, "Blue bag");
        long socks = repository.addItem(tripId, "Socks", 3, person, bag);
        repository.setPacked(tripId, socks, true);
        repository.addItem(tripId, "Scarf", 1, null, null);
        String english = repository.exportTrip(tripId, true);
        assertTrue(english.startsWith("Coast\n2026-10-10 — 2026-10-12\nPacked: 1/2\n"));
        assertTrue(english.contains("[✓] Socks × 3 · Alex · Blue bag"));
        assertTrue(english.contains("[ ] Scarf × 1"));
        assertTrue(repository.exportTrip(tripId, false).contains("Собрано: 1/2"));
    }

    @Test public void foreignReferencesAreRejectedWithoutPartialMutation() {
        long otherTrip = repository.createTrip("Other", "", "", Template.EMPTY, true);
        long foreignPerson = repository.addPerson(otherTrip, "Alex");
        long foreignBag = repository.addBag(otherTrip, "Bag");
        int writes = storage.writes;
        expectIllegal(() -> repository.addItem(tripId, "Bad", 1, foreignPerson, null));
        expectIllegal(() -> repository.addItem(tripId, "Bad", 1, null, foreignBag));
        assertEquals(writes, storage.writes);
        assertEquals(0, repository.getTrip(tripId).items.size());
    }

    @Test public void writeFailureDoesNotPublishOrLoseEarlierData() {
        long saved = repository.addItem(tripId, "Saved", 1, null, null);
        storage.fail = true;
        try {
            repository.addItem(tripId, "Unsaved", 1, null, null);
            fail("Write should fail");
        } catch (IllegalStateException expected) {
            assertEquals("disk failure", expected.getMessage());
        }
        assertEquals(Arrays.asList(saved), ids(repository.getTrip(tripId).items));
        assertEquals(Arrays.asList(saved), ids(new PackingRepository(storage).getTrip(tripId).items));
        storage.fail = false;
        long next = repository.addItem(tripId, "Retry", 1, null, null);
        assertTrue(next > saved);
    }

    @Test public void failedUndoCanBeRetriedAfterStorageRecovers() {
        long item = repository.addItem(tripId, "Book", 1, null, null);
        PackingRepository.UndoToken undo = repository.deleteItem(tripId, item);
        storage.fail = true;
        try { repository.undo(undo); fail("Write should fail"); }
        catch (IllegalStateException expected) { /* Previous deletion remains committed. */ }
        assertEquals(0, repository.getTrip(tripId).items.size());
        storage.fail = false;
        assertTrue(repository.undo(undo));
        assertEquals(item, repository.getTrip(tripId).items.get(0).id);
    }

    @Test public void undoCannotCrossRepositoriesOrMissingTrips() {
        long item = repository.addItem(tripId, "Book", 1, null, null);
        PackingRepository.UndoToken undo = repository.deleteItem(tripId, item);
        assertFalse(new PackingRepository(storage).undo(undo));
        repository.deleteTrip(tripId);
        assertFalse(repository.undo(undo));
    }

    @Test public void invalidDatesAndBlankNamesCannotCreatePartialTrips() {
        int writes = storage.writes;
        expectIllegal(() -> repository.createTrip(" ", "", "", Template.EMPTY, true));
        expectIllegal(() -> repository.createTrip("Bad", "2026-02-30", "", Template.EMPTY, true));
        expectIllegal(() -> repository.createTrip("Bad", "03.10.2026", "", Template.EMPTY, true));
        expectIllegal(() -> repository.createTrip("Bad", "2026-10-12", "2026-10-11", Template.EMPTY, true));
        assertEquals(writes, storage.writes);
        assertEquals(1, repository.listTrips().size());
    }

    @Test public void snapshotsAndCollectionsCannotBeMutatedByTheScreen() {
        Trip before = repository.getTrip(tripId);
        repository.addItem(tripId, "New", 1, null, null);
        assertEquals(0, before.items.size());
        try { repository.listTrips().clear(); fail("Trips should be immutable"); }
        catch (UnsupportedOperationException expected) { }
        try { repository.getTrip(tripId).items.clear(); fail("Items should be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    private static Item find(Trip trip, long id) {
        for (Item item : trip.items) if (item.id == id) return item;
        throw new AssertionError("Missing item " + id);
    }

    private static List<Long> ids(List<Item> items) {
        List<Long> ids = new ArrayList<>();
        for (Item item : items) ids.add(item.id);
        return ids;
    }

    private static List<Long> tripIds(List<Trip> trips) {
        List<Long> ids = new ArrayList<>();
        for (Trip trip : trips) ids.add(trip.id);
        return ids;
    }

    private static void expectIllegal(Runnable action) {
        try { action.run(); fail("Expected validation error"); }
        catch (IllegalArgumentException expected) { }
    }

    private static final class MemoryPersistence implements PackingRepository.Persistence {
        State saved = State.empty();
        int writes;
        boolean fail;
        @Override public State read() { return saved; }
        @Override public void write(State state) {
            if (fail) throw new IllegalStateException("disk failure");
            saved = state;
            writes++;
        }
    }
}
