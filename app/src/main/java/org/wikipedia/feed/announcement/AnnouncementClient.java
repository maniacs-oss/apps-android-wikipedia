package org.wikipedia.feed.announcement;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;

import org.wikipedia.BuildConfig;
import org.wikipedia.auth.AccountUtil;
import org.wikipedia.dataclient.WikiSite;
import org.wikipedia.dataclient.retrofit.RetrofitFactory;
import org.wikipedia.feed.FeedCoordinator;
import org.wikipedia.feed.dataclient.FeedClient;
import org.wikipedia.feed.model.Card;
import org.wikipedia.settings.Prefs;
import org.wikipedia.util.GeoUtil;
import org.wikipedia.util.ReleaseUtil;
import org.wikipedia.util.log.L;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.http.GET;
import retrofit2.http.Headers;

import static org.wikipedia.Constants.ACCEPT_HEADER_PREFIX;

public class AnnouncementClient implements FeedClient {
    private static final String PLATFORM_CODE = "AndroidApp";
    private static final String PLATFORM_CODE_NEW = "AndroidAppV2";

    @Nullable private Call<AnnouncementList> call;

    @Override
    public void request(@NonNull Context context, @NonNull WikiSite wiki, int age, @NonNull Callback cb) {
        cancel();
        String endpoint = String.format(Locale.ROOT, Prefs.getRestbaseUriFormat(), wiki.scheme(),
                wiki.authority());
        Retrofit retrofit = RetrofitFactory.newInstance(endpoint, wiki);
        Service service = retrofit.create(Service.class);
        call = request(service);
        call.enqueue(new CallbackAdapter(cb, true));
    }

    @Override
    public void cancel() {
        if (call == null) {
            return;
        }
        call.cancel();
        call = null;
    }

    @VisibleForTesting
    interface Service {
        /**
         * Gets a list of announcements that are currently in effect.
         */
        @NonNull
        @Headers(ACCEPT_HEADER_PREFIX + "announcements/0.1.0\"")
        @GET("feed/announcements")
        Call<AnnouncementList> get();
    }

    @VisibleForTesting
    @NonNull
    Call<AnnouncementList> request(@NonNull Service service) {
        return service.get();
    }

    @VisibleForTesting
    static class CallbackAdapter implements retrofit2.Callback<AnnouncementList> {
        @NonNull private final Callback cb;
        private final boolean postDelayed;

        CallbackAdapter(@NonNull Callback cb, boolean postDelayed) {
            this.cb = cb;
            this.postDelayed = postDelayed;
        }

        @Override public void onResponse(@NonNull Call<AnnouncementList> call,
                                         @NonNull Response<AnnouncementList> response) {
            List<Card> cards = new ArrayList<>();
            AnnouncementList content = response.body();
            if (content != null) {
                cards.addAll(buildCards(content.items()));
            }
            if (postDelayed) {
                FeedCoordinator.postCardsToCallback(cb, cards);
            } else {
                cb.success(cards);
            }
        }

        @Override public void onFailure(@NonNull Call<AnnouncementList> call, @NonNull Throwable caught) {
            L.v(caught);
            cb.error(caught);
        }
    }

    @VisibleForTesting
    static List<Card> buildCards(@NonNull List<Announcement> announcements) {
        List<Card> cards = new ArrayList<>();
        String country = GeoUtil.getGeoIPCountry();
        Date now = new Date();
        for (Announcement announcement : announcements) {
            if (shouldShow(announcement, country, now)) {
                switch (announcement.type()) {
                    case Announcement.SURVEY:
                        cards.add(new SurveyCard(announcement));
                        break;
                    case Announcement.FUNDRAISING:
                        cards.add(new FundraisingCard(announcement));
                        break;
                    default:
                        cards.add(new AnnouncementCard(announcement));
                        break;
                }
            }
        }
        return cards;
    }

    @VisibleForTesting
    static boolean shouldShow(@Nullable Announcement announcement,
                              @Nullable String country,
                              @NonNull Date date) {
        if (announcement == null
                || !(announcement.platforms().contains(PLATFORM_CODE) || announcement.platforms().contains(PLATFORM_CODE_NEW))
                || TextUtils.isEmpty(country)
                || !announcement.countries().contains(country)
                || (announcement.startTime() != null && announcement.startTime().after(date))
                || (announcement.endTime() != null && announcement.endTime().before(date))
                || !matchesVersionCodes(announcement.minVersion(), announcement.maxVersion())
                || !matchesConditions(announcement)) {
            return false;
        }
        return true;
    }

    private static boolean matchesConditions(@NonNull Announcement announcement) {
        if (announcement.beta() != null && (announcement.beta() != ReleaseUtil.isPreProdRelease())) {
            return false;
        }
        if (announcement.loggedIn() != null && (announcement.loggedIn() != AccountUtil.isLoggedIn())) {
            return false;
        }
        if (announcement.readingListSyncEnabled() != null && (announcement.readingListSyncEnabled() != Prefs.isReadingListSyncEnabled())) {
            return false;
        }
        return true;
    }

    private static boolean matchesVersionCodes(@Nullable String minVersion, @Nullable String maxVersion) {
        try {
            if (!TextUtils.isEmpty(minVersion) && Integer.parseInt(minVersion) > BuildConfig.VERSION_CODE) {
                return false;
            }
            if (!TextUtils.isEmpty(maxVersion) && Integer.parseInt(maxVersion) < BuildConfig.VERSION_CODE) {
                return false;
            }
        } catch (NumberFormatException e) {
            // ignore
        }
        return true;
    }
}
