package com.jonkryl.packinglist;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Until;
import com.jonkryl.packinglist.domain.PackingModels.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;
import static com.jonkryl.packinglist.JourneySupport.*;

@RunWith(AndroidJUnit4.class)
public final class PackingJourneyTest {
    @Test public void createEditDuplicatePackFilterUndoAndCopy() throws Exception {
        locale("en");
        assertOffline();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            try {
            acceptContextualIfNeeded();
            view(R.id.add_button).perform(click());
            input(R.id.trip_name, TITLE);
            input(R.id.start_date, "2026-10-10");
            input(R.id.end_date, "2026-10-12");
            save();
            assertEquals(TITLE, trip(scenario).title);
            assertEquals("2026-10-10", trip(scenario).startDate);

            addOwner(true, "Ada"); addOwner(true, "Boris");
            addOwner(false, "Main bag"); addOwner(false, "Day bag");
            addItem("Bottle", "1", "Ada", "Main bag");
            addItem("Bottle", "2", "Boris", "Day bag");
            addItem("Passport", "1", "Shared / no person", "No bag");
            Trip initial = trip(scenario);
            assertEquals(3, initial.items.size());
            assertNotEquals(initial.items.get(0).id, initial.items.get(1).id);
            assertNotEquals(initial.items.get(0).personId, initial.items.get(1).personId);
            assertNotEquals(initial.items.get(0).bagId, initial.items.get(1).bagId);

            mainText("Bottle × 1");
            long[] idsBefore = adapterIds(scenario);
            long packedId = initial.items.get(0).id;
            focusedView(withTagValue(is("packed_" + packedId))).perform(click());
            assertTrue(trip(scenario).items.get(0).packed);
            assertArrayEquals("Packing marks must retain row IDs and order", idsBefore, adapterIds(scenario));
            screenshot("01-en-trip");

            clickMainId("remaining_filter");
            scenario.onActivity(activity -> assertEquals(2,
                    activity.repositoryForTests().visibleItems(activity.currentTripIdForTests(), true, Grouping.NONE).size()));
            assertFalse("Packed row is filtered from the adapter", containsId(adapterIds(scenario), packedId));
            screenshot("02-en-remaining");
            clickMainId("remaining_filter");

            mainText("Bottle × 1"); clickText("Bottle × 1");
            input(R.id.item_quantity, "3"); save();
            assertEquals(3, trip(scenario).items.get(0).quantity);
            view(R.id.undo_button).perform(click());
            assertEquals(1, trip(scenario).items.get(0).quantity);
            mainText("Bottle × 1"); clickText("Bottle × 1");
            input(R.id.item_quantity, "3"); save();

            mainText("Passport × 1");
            focusedView(withContentDescription("Item actions Passport")).perform(click());
            clickText("Delete item");
            assertEquals(2, trip(scenario).items.size());
            scenario.recreate();
            assertEquals("Deleted item stays deleted until undo after Activity recreation", 2, trip(scenario).items.size());
            view(R.id.undo_button).check(matches(isDisplayed())).perform(click());
            assertEquals(3, trip(scenario).items.size());
            assertEquals(initial.items.get(2).id, trip(scenario).items.get(2).id);

            renameOwner(true, "Ada", "Ada Rivera");
            renameOwner(false, "Main bag", "Cabin bag");
            deleteOwnerAndUndo(true, "Boris", scenario);
            deleteOwnerAndUndo(false, "Day bag", scenario);
            assertEquals("Ada Rivera", trip(scenario).personName(trip(scenario).items.get(0).personId));
            assertEquals("Cabin bag", trip(scenario).bagName(trip(scenario).items.get(0).bagId));

            clickMainId("group_button"); clickText("By person");
            mainText("Ada Rivera"); screenshot("03-en-by-person");
            clickMainId("group_button"); clickText("By bag");
            mainText("Cabin bag"); screenshot("04-en-by-bag");
            clickMainId("group_button"); clickText("In order");

            Trip original = trip(scenario);
            tripMenu(TITLE); clickText("Copy trip");
            input(R.id.copy_name, TITLE + " reset");
            view(R.id.reset_marks).check(matches(isChecked()));
            save();
            Trip resetCopy = trip(scenario);
            assertEquals(0, resetCopy.packedCount());
            assertEquals(original.items.size(), resetCopy.items.size());
            assertEquals(3, resetCopy.items.get(0).quantity);
            assertNotEquals(original.items.get(0).id, resetCopy.items.get(0).id);
            assertEquals("Ada Rivera", resetCopy.personName(resetCopy.items.get(0).personId));

            openOriginalFromHome();
            tripMenu(TITLE); clickText("Copy trip");
            input(R.id.copy_name, TITLE + " kept");
            view(R.id.reset_marks).perform(scrollTo(), click());
            save();
            assertEquals(1, trip(scenario).packedCount());
            openOriginalFromHome();
            assertEquals(TITLE, trip(scenario).title);
            assertEquals(1, trip(scenario).packedCount());
            assertEquals(3, trip(scenario).items.get(0).quantity);

            chooseLanguage("ru");
            screenshot("05-ru-trip");
            view(R.id.add_button).check(matches(withText("＋ Добавить вещь")));
            clickMainId("remaining_filter");
            view(R.id.remaining_filter).check(matches(withText("Осталось собрать"))).check(matches(isChecked()));
            screenshot("06-ru-remaining");
            clickMainId("remaining_filter");
            chooseLanguage("en");

            // Inject real system Back: the trip callback returns home; another Back leaves the app.
            androidx.test.espresso.Espresso.pressBack();
            scenario.onActivity(activity -> assertEquals(-1L, activity.currentTripIdForTests()));
            view(R.id.add_button).check(matches(withText("＋ New trip")));
            androidx.test.espresso.Espresso.pressBackUnconditionally();
            assertTrue("Back from the trip list must leave the app foreground",
                    device().wait(Until.gone(By.res(PACKAGE, "add_button")), 5000));
            } catch (Exception | AssertionError failure) {
                captureFailure("failure-journey-api" + android.os.Build.VERSION.SDK_INT);
                throw failure;
            }
        }
        // Restore the original trip through the UI for the separate process-restart invocation.
        try (ActivityScenario<MainActivity> restored = ActivityScenario.launch(MainActivity.class)) {
            openOriginalFromHome();
            assertEquals(TITLE, trip(restored).title);
            assertEquals(1, trip(restored).packedCount());
            assertEquals(3, trip(restored).items.get(0).quantity);
        }
    }

    private void addOwner(boolean person, String name) throws Exception {
        clickMainId("owners_button");
        clickText(person ? "＋ Add person" : "＋ Add bag");
        input(R.id.owner_name, name); save();
    }

    private void renameOwner(boolean person, String oldName, String newName) throws Exception {
        clickMainId("owners_button");
        clickText((person ? "Person: " : "Bag: ") + oldName);
        clickText("Rename"); input(R.id.owner_name, newName); save();
    }

    private void deleteOwnerAndUndo(boolean person, String name, ActivityScenario<MainActivity> scenario) throws Exception {
        Trip before = trip(scenario);
        clickMainId("owners_button");
        clickText((person ? "Person: " : "Bag: ") + name);
        clickText("Delete (keep items)");
        Trip deleted = trip(scenario);
        assertEquals(before.items.size(), deleted.items.size());
        assertEquals(1, person ? deleted.people.size() : deleted.bags.size());
        view(R.id.undo_button).perform(click());
        Trip restored = trip(scenario);
        assertEquals(2, person ? restored.people.size() : restored.bags.size());
        assertEquals(before.items.get(1).personId, restored.items.get(1).personId);
        assertEquals(before.items.get(1).bagId, restored.items.get(1).bagId);
    }

    private void addItem(String name, String quantity, String person, String bag) {
        view(R.id.add_button).perform(click());
        input(R.id.item_name, name); input(R.id.item_quantity, quantity);
        spinner(R.id.person_spinner, person); spinner(R.id.bag_spinner, bag); save();
    }

    private static long[] adapterIds(ActivityScenario<MainActivity> scenario) {
        AtomicReference<long[]> result = new AtomicReference<>();
        scenario.onActivity(activity -> {
            RecyclerView.Adapter<?> adapter = ((RecyclerView) activity.findViewById(R.id.main_list)).getAdapter();
            assertNotNull(adapter); assertTrue(adapter.hasStableIds());
            long[] values = new long[adapter.getItemCount()];
            for (int i = 0; i < values.length; i++) values[i] = adapter.getItemId(i);
            result.set(values);
        });
        return result.get();
    }

    private static boolean containsId(long[] values, long id) {
        for (long value : values) if (value == id) return true;
        return false;
    }
}
