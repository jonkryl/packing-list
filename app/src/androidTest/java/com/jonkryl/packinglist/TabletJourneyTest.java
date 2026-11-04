package com.jonkryl.packinglist;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import com.jonkryl.packinglist.domain.PackingRepository;
import com.jonkryl.packinglist.domain.SQLitePersistence;
import com.jonkryl.packinglist.domain.PackingModels.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;
import static com.jonkryl.packinglist.JourneySupport.*;

/** Executed only by the explicitly owned API36 tablet profile, after phone journeys. */
@RunWith(AndroidJUnit4.class)
public final class TabletJourneyTest {
    @Test public void tripsPackingDraftAndReadingPositionSurviveTabletRotation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("true", InstrumentationRegistry.getArguments().getString("ownedPortfolioEmulator"));
        assertEquals(36, Build.VERSION.SDK_INT);
        assertTrue(BuildConfig.DEBUG && BuildConfig.ADS_TEST_MODE);
        assertEquals("demo-banner-yandex", BuildConfig.BANNER_ID);
        assertTrue(Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"));
        assertOffline();
        locale("en");
        long coast, work;
        // Synthetic setup uses the real SQLite repository; subsequent actions use actual UI.
        try (SQLitePersistence storage = new SQLitePersistence(context)) {
            PackingRepository repository = new PackingRepository(storage);
            coast = repository.createTrip("Tablet coast", "", "", Template.EMPTY, true);
            work = repository.createTrip("Tablet work", "", "", Template.EMPTY, true);
            for (int i = 1; i <= 30; i++) repository.addItem(coast, "Tablet item " + i, 1, null, null);
            repository.addItem(work, "Work notebook", 1, null, null);
        }
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().putLong("trip", coast).commit();
        device().setOrientationNatural();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            try {
                acceptContextualIfNeeded();
                awaitWidth(scenario, true);
                assertLayout(scenario, true);
                openFromNavigation("Tablet work");
                assertEquals(work, trip(scenario).id);
                mainText("Work notebook × 1");
                openFromNavigation("Tablet coast");
                assertEquals(coast, trip(scenario).id);
                clickMainId("search_button");
                input(R.id.search_query, "Tablet item");view(android.R.id.button1).perform(click());
                clickMainId("remaining_filter");
                long packed = trip(scenario).items.get(0).id;
                mainText("Tablet item 1 × 1");clickPackingMark(packed);
                assertTrue(trip(scenario).items.get(0).packed);
                scenario.recreate();
                settle();
                view(R.id.undo_button).check(matches(isDisplayed())).perform(click());
                assertFalse("Undo survives the adaptive Activity recreation", trip(scenario).items.get(0).packed);
                mainText("Tablet item 18 × 1");
                Anchor anchor = anchor(scenario);
                String saved = exported(scenario);
                screenshot("tablet-01-landscape-items");
                scenario.recreate();
                settle();
                assertAnchor(anchor, anchor(scenario));
                device().setOrientationLeft();
                awaitWidth(scenario, false);
                assertLayout(scenario, false);
                assertEquals(coast, trip(scenario).id);
                assertAnchor(anchor, anchor(scenario));
                assertEquals("Rotation does not change the stored packing list", saved, exported(scenario));
                clickMainId("search_button");
                view(R.id.search_query).check(matches(withText("Tablet item")));
                view(android.R.id.button2).perform(click());
                mainId("remaining_filter");view(R.id.remaining_filter).check(matches(isChecked()));
                screenshot("tablet-02-portrait-items");
                device().setOrientationNatural();
                awaitWidth(scenario, true);
                assertLayout(scenario, true);
                view(R.id.add_button).perform(click());
                view(R.id.item_name).perform(scrollTo(), click(), typeText("Tablet gloves"));
                view(R.id.item_name).check((target,error)->{
                    if(error!=null)throw error;
                    assertTrue("The tablet editor uses a bounded readable dialog",
                            target.getRootView().getWidth()/target.getResources().getDisplayMetrics().density<=600f);
                    WindowInsets insets=target.getRootWindowInsets();
                    assertNotNull(insets);
                    assertTrue("The real soft keyboard is visible while editing",insets.isVisible(WindowInsets.Type.ime()));
                });
                view(android.R.id.button1).check(matches(isDisplayed()));
                screenshot("tablet-03-editor-keyboard");
                device().setOrientationLeft();
                awaitWidth(scenario, false);
                view(R.id.item_name).check(matches(withText("Tablet gloves")));
                input(R.id.item_quantity,"2");save();
                Trip finalTrip=trip(scenario);
                assertEquals(31,finalTrip.items.size());
                Item gloves=finalTrip.items.get(finalTrip.items.size()-1);
                assertEquals("Tablet gloves",gloves.name);assertEquals(2,gloves.quantity);
                assertFalse("The new item is not accidentally packed",gloves.packed);
            } catch(Exception|AssertionError failure){captureFailure("failure-tablet-api"+Build.VERSION.SDK_INT);throw failure;}
        } finally {device().setOrientationNatural();device().unfreezeRotation();}
    }

    private void openFromNavigation(String title) throws Exception {
        device();
        assertTrue(new UiScrollable(new UiSelector().resourceId(PACKAGE+":id/trip_navigation"))
                .setMaxSearchSwipes(12).scrollIntoView(new UiSelector().description("Open trip "+title)));
        focusedView(withContentDescription("Open trip "+title)).perform(click());
    }
    private void awaitWidth(ActivityScenario<MainActivity> scenario,boolean wide) throws Exception {
        long deadline=android.os.SystemClock.uptimeMillis()+10000;
        while(android.os.SystemClock.uptimeMillis()<deadline){
            AtomicReference<Boolean> ready=new AtomicReference<>(false);
            scenario.onActivity(activity->{View pane=activity.findViewById(R.id.trip_pane);ready.set(pane!=null&&(pane.getVisibility()==View.VISIBLE)==wide&&activity.findViewById(R.id.main_list).getHeight()>0);});
            if(ready.get()){settle();return;}
            Thread.sleep(50);
        }
        fail("Tablet window did not reach the requested adaptive layout");
    }
    private void settle(){device().waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    private static Rect bounds(View view){int[] xy=new int[2];view.getLocationOnScreen(xy);return new Rect(xy[0],xy[1],xy[0]+view.getWidth(),xy[1]+view.getHeight());}
    private void assertLayout(ActivityScenario<MainActivity> scenario,boolean wide){scenario.onActivity(activity->{
        View main=activity.findViewById(R.id.main_list),nav=activity.findViewById(R.id.trip_navigation),action=activity.findViewById(R.id.add_button),ad=activity.findViewById(R.id.ad_container);
        float density=activity.getResources().getDisplayMetrics().density;
        android.util.Log.i("PackedTabletJourney","actual workspace="+bounds(activity.findViewById(R.id.workspace))+" density="+density+" wide="+wide);
        assertTrue(bounds(main).bottom<=bounds(action).top);
        if(ad.getVisibility()==View.VISIBLE&&ad.getHeight()>0)assertTrue("Fixed banner stays below the action",bounds(action).bottom<=bounds(ad).top);
        if(wide){assertTrue(nav.isShown());assertTrue("Trip navigation and items do not overlap",bounds(nav).right<=bounds(main).left);}
        else {assertFalse(nav.isShown());assertTrue("The single column is readable",activity.findViewById(R.id.workspace).getWidth()/density<=720.5f);}
    });}
    private static final class Anchor{long id;int offset;Anchor(long id,int offset){this.id=id;this.offset=offset;}}
    private Anchor anchor(ActivityScenario<MainActivity> scenario){AtomicReference<Anchor> result=new AtomicReference<>();scenario.onActivity(activity->{RecyclerView view=activity.findViewById(R.id.main_list);LinearLayoutManager layout=(LinearLayoutManager)view.getLayoutManager();int first=layout.findFirstVisibleItemPosition();assertTrue(first>=0);result.set(new Anchor(view.getAdapter().getItemId(first),layout.findViewByPosition(first).getTop()-view.getPaddingTop()));});return result.get();}
    private void assertAnchor(Anchor expected,Anchor actual){assertEquals("The same stable row remains at the top",expected.id,actual.id);assertEquals("The row keeps its reading offset",expected.offset,actual.offset,2);}
    private String exported(ActivityScenario<MainActivity> scenario){AtomicReference<String> value=new AtomicReference<>();scenario.onActivity(activity->value.set(activity.repositoryForTests().exportTrip(activity.currentTripIdForTests(),true)));return value.get();}
}
