package com.jonkryl.packinglist;

import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiObject2;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;
import static com.jonkryl.packinglist.JourneySupport.*;

/** device-journey.sh sets the actual system font_scale=2.0 before starting this process. */
@RunWith(AndroidJUnit4.class)
public final class LargeFontAccessibilityTest {
    @Test public void packingAndScrollableFormsRemainUsableAtTwoHundredPercent() throws Exception {
        locale("en");
        assertOffline();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(2f, activity.getResources().getConfiguration().fontScale, 0.01f);
                View add = activity.findViewById(R.id.add_button);
                assertTrue(add.getHeight() >= 48 * activity.getResources().getDisplayMetrics().density);
            });
            assertEquals(TITLE, trip(scenario).title);
            clickMainId("remaining_filter");
            view(R.id.remaining_filter).check(matches(isChecked()));
            screenshot("07-en-font-200-remaining");
            clickMainId("remaining_filter");

            view(R.id.add_button).perform(click());
            input(R.id.item_name, "Large font check");
            input(R.id.item_quantity, "2");
            view(R.id.bag_spinner).perform(scrollTo());
            UiObject2 bag = device().findObject(By.res(PACKAGE, "bag_spinner"));
            assertInsideScreen(bag);
            screenshot("08-en-font-200-form");
            view(android.R.id.button2).perform(click());
            assertEquals(3, trip(scenario).items.size());
            locale("ru"); scenario.recreate();
            screenshot("09-ru-font-200-trip");
            view(R.id.add_button).check(matches(withText("＋ Добавить вещь")));
        }
    }
}
