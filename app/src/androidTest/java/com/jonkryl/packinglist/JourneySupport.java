package com.jonkryl.packinglist;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.LocaleList;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.jonkryl.packinglist.domain.PackingModels.Trip;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.*;

final class JourneySupport {
    static final String TITLE = "Journey coast";
    static final String PACKAGE = "com.jonkryl.packinglist";
    static UiDevice device() { return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()); }

    static void assertOffline() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        assertNotNull(manager);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            assertTrue("Offline journey must have no network with INTERNET capability",
                    capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
        }
        android.util.Log.i("SobranoJourney", "OFFLINE verified: no network with INTERNET capability");
    }

    @SuppressWarnings("deprecation")
    static void locale(String language) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Locale value = new Locale(language);
            Locale.setDefault(value);
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Configuration config = new Configuration(context.getResources().getConfiguration());
            config.setLocales(new LocaleList(value));
            context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());
        });
    }

    static void acceptContextualIfNeeded() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences preferences = context.getSharedPreferences("ad_privacy", Context.MODE_PRIVATE);
        if (preferences.getBoolean("choice_set", false)) return;
        assertNotNull("First launch requires the ad privacy dialog",
                device().wait(Until.findObject(By.text("Ad privacy")), 5000));
        UiObject2 contextual = device().wait(Until.findObject(By.text("Contextual ads").enabled(true)), 2000);
        if (contextual == null || contextual.getVisibleBounds().isEmpty()) {
            UiSelector selector = new UiSelector().className("android.widget.ScrollView").scrollable(true);
            assertTrue("Hidden privacy choice requires an actual scroll container",
                    device().findObject(selector).waitForExists(3000));
            assertTrue("Contextual privacy choice must become visible",
                    new UiScrollable(selector).setMaxSearchSwipes(4).scrollTextIntoView("Contextual ads"));
            contextual = device().wait(Until.findObject(By.text("Contextual ads").enabled(true)), 3000);
        }
        assertInsideScreen(contextual);
        contextual.click();
        assertTrue("Privacy dialog must close after choosing contextual advertising",
                device().wait(Until.gone(By.text("Ad privacy")), 5000));
        assertTrue("The required privacy choice must be persisted", preferences.getBoolean("choice_set", false));
        assertFalse("Contextual advertising must not grant personalization", preferences.getBoolean("personalized", true));
        device().waitForIdle();
    }

    static UiObject2 text(String value) {
        UiObject2 object = device().wait(Until.findObject(By.text(value)), 5000);
        assertNotNull("Visible text not found: " + value, object);
        return object;
    }

    static void clickText(String value) { text(value).click(); device().waitForIdle(); }

    static void mainText(String value) throws Exception {
        assertTrue("Packing list could not scroll to " + value,
                new UiScrollable(new UiSelector().resourceId(PACKAGE + ":id/main_list"))
                        .setMaxSearchSwipes(20).scrollTextIntoView(value));
        device().waitForIdle();
    }

    static void mainId(String value) throws Exception {
        assertTrue("Packing control is absent: " + value,
                new UiScrollable(new UiSelector().resourceId(PACKAGE + ":id/main_list"))
                        .setMaxSearchSwipes(20).scrollIntoView(new UiSelector().resourceId(PACKAGE + ":id/" + value)));
        device().waitForIdle();
    }

    static void clickMainId(String value) throws Exception {
        mainId(value);
        UiObject2 object = device().findObject(By.res(PACKAGE, value));
        assertNotNull(object); object.click(); device().waitForIdle();
    }

    static void input(int id, String value) {
        onView(withId(id)).perform(scrollTo(), replaceText(value), androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
    }

    static void save() {
        onView(withId(android.R.id.button1)).perform(click());
        device().waitForIdle();
    }

    static void spinner(int id, String value) {
        onView(withId(id)).perform(scrollTo(), click());
        onData(equalTo(value)).perform(click());
    }

    static Trip trip(ActivityScenario<MainActivity> scenario) {
        AtomicReference<Trip> result = new AtomicReference<>();
        scenario.onActivity(activity -> result.set(activity.repositoryForTests().getTrip(activity.currentTripIdForTests())));
        assertNotNull("An existing trip must be open", result.get());
        return result.get();
    }

    static void screenshot(String name) {
        device().waitForIdle();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getExternalFilesDir(null), "screenshots");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        assertTrue("Could not save actual app screenshot", device().takeScreenshot(new File(directory, name + ".png")));
    }

    static void captureFailure(String name) {
        try {
            screenshot(name);
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            File directory = new File(context.getExternalFilesDir(null), "screenshots");
            device().dumpWindowHierarchy(new File(directory, name + ".xml"));
        } catch (Exception | AssertionError captureError) {
            android.util.Log.w("SobranoJourney", "Could not capture failure proof", captureError);
        }
    }

    static void tripMenu(String title) throws Exception {
        mainId("owners_button");
        UiObject2 action = device().wait(Until.findObject(By.desc("Trip actions " + title)), 5000);
        assertNotNull(action); action.click(); device().waitForIdle();
    }

    static void openOriginalFromHome() throws Exception {
        UiObject2 back = device().findObject(By.desc("All trips"));
        if (back != null) back.click();
        mainText(TITLE);
        UiObject2 label = text(TITLE);
        UiObject2 card = label.getParent().getParent();
        UiObject2 open = card.findObject(By.text("Open list →"));
        assertNotNull("Original trip open action must exist", open);
        open.click(); device().waitForIdle();
    }

    static void assertInsideScreen(UiObject2 object) {
        assertNotNull(object);
        Rect bounds = object.getVisibleBounds();
        assertTrue("Control has a visible touch area", bounds.width() > 0 && bounds.height() > 0);
        assertTrue(bounds.left >= 0 && bounds.top >= 0
                && bounds.right <= device().getDisplayWidth() && bounds.bottom <= device().getDisplayHeight());
    }
}
