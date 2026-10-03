package com.jonkryl.packinglist;

import android.content.Context;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.view.View;
import androidx.test.espresso.Root;
import androidx.test.espresso.ViewInteraction;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.jonkryl.packinglist.domain.PackingModels.Trip;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.anyOf;
import static org.junit.Assert.*;

final class JourneySupport {
    static final String TITLE = "Journey coast";
    static final String PACKAGE = "com.jonkryl.packinglist";
    static UiDevice device() { return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()); }

    private static Matcher<Root> focusedRoot(Integer targetId) {
        return new TypeSafeMatcher<Root>() {
            @Override public void describeTo(Description description) {
                description.appendText("focused visible window");
                if (targetId != null) description.appendText(" containing view ID ").appendValue(targetId);
            }
            @Override protected boolean matchesSafely(Root root) {
                View decor = root.getDecorView();
                return decor.hasWindowFocus() && decor.getWindowVisibility() == View.VISIBLE
                        && !decor.isLayoutRequested()
                        && (targetId == null || decor.findViewById(targetId) != null);
            }
        };
    }

    static ViewInteraction view(int id) {
        // Espresso waits for this exact focused window, instead of using a stale parent dialog.
        return onView(withId(id)).inRoot(focusedRoot(id));
    }

    static ViewInteraction focusedView(Matcher<View> matcher) {
        return onView(matcher).inRoot(focusedRoot(null));
    }

    private static boolean containsAdapterView(View view) {
        if (view instanceof android.widget.AdapterView) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsAdapterView(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static Matcher<Root> spinnerPopupRoot() {
        return new TypeSafeMatcher<Root>() {
            @Override public void describeTo(Description description) {
                description.appendText("focused native spinner popup containing an AdapterView");
            }
            @Override protected boolean matchesSafely(Root root) {
                View decor = root.getDecorView();
                return isPlatformPopup().matches(root) && decor.hasWindowFocus()
                        && !decor.isLayoutRequested() && containsAdapterView(decor);
            }
        };
    }

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

    static void locale(String language) {
        // Fixture setup only, before launch. Post-launch changes use the real language menu below.
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .edit().putString("language", language).commit());
    }

    static void chooseLanguage(String language) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        String current = preferences.getString("language", "system");
        focusedView(anyOf(withContentDescription("Menu"), withContentDescription("Меню"))).perform(click());
        clickText("ru".equals(current) ? "Язык" : "Language");
        clickText("ru".equals(language) ? "Русский" : "English");
        String expected = "ru".equals(language) ? "＋ Добавить вещь" : "＋ Add item";
        assertNotNull("Language menu must recreate the actual app with translated controls",
                device().wait(Until.findObject(By.res(PACKAGE, "add_button").text(expected)), 5000));
        assertEquals("Language choice is saved by the real menu", language, preferences.getString("language", "system"));
        view(R.id.add_button).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText(expected)));
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

    static void clickText(String value) {
        text(value);
        focusedView(withText(value)).perform(click());
    }

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
        int id = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getResources().getIdentifier(value, "id", PACKAGE);
        assertNotEquals("Expected packing control ID", 0, id);
        view(id).perform(click());
    }

    static void input(int id, String value) {
        view(id).perform(scrollTo(), replaceText(value), androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
    }

    static void save() {
        view(android.R.id.button1).perform(click());
    }

    static void spinner(int id, String value) {
        view(id).perform(scrollTo()).check((target, error) -> {
            if (error != null) throw error;
            android.widget.Spinner spinner = (android.widget.Spinner) target;
            android.widget.SpinnerAdapter adapter = spinner.getAdapter();
            assertNotNull("Spinner must have its actual product adapter", adapter);
            boolean found = false;
            for (int i = 0; i < adapter.getCount(); i++) found |= value.equals(String.valueOf(adapter.getItem(i)));
            android.util.Log.i("SobranoJourney", "Open spinner " + target.getResources().getResourceEntryName(id)
                    + " requested=" + value + " adapterCount=" + adapter.getCount() + " selected=" + spinner.getSelectedItem());
            assertTrue("Requested owner must exist in the actual spinner adapter: " + value, found);
            Rect bounds = new Rect();
            assertTrue("Spinner must expose a real visible touch area", target.getGlobalVisibleRect(bounds));
        });
        String resourceName = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getResources().getResourceEntryName(id);
        UiObject2 widget = device().wait(Until.findObject(By.res(PACKAGE, resourceName)), 5000);
        assertInsideScreen(widget);
        // Accessibility bounds are in screen space; View global bounds are local to a dialog's root.
        Rect bounds = widget.getVisibleBounds();
        android.util.Log.i("SobranoJourney", "Spinner screen tap " + resourceName + " bounds=" + bounds.toShortString());
        // API 24 CI logged Espresso turning an overslept tap into a long press. UiDevice uses a fast tap.
        assertTrue("Native spinner opener must inject a real tap", device().click(bounds.centerX(), bounds.centerY()));
        onView(isRoot()).inRoot(spinnerPopupRoot()).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()));
        // Adapter selection can scroll; a visible-text precondition would reject valid off-screen rows.
        onData(equalTo(value)).inRoot(spinnerPopupRoot()).perform(click());
        view(id).check(androidx.test.espresso.assertion.ViewAssertions.matches(withSpinnerText(value)));
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
        assertNotNull(action); focusedView(withContentDescription("Trip actions " + title)).perform(click());
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
