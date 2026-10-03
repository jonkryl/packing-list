package com.jonkryl.packinglist;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.jonkryl.packinglist.domain.PackingRepository;
import com.jonkryl.packinglist.domain.SQLitePersistence;

/** Keeps a local repository and its memory-only undo across Activity configuration changes. */
public final class PackingState extends AndroidViewModel {
    private final SQLitePersistence persistence;
    public final PackingRepository repository;
    public PackingRepository.UndoToken undo;

    public PackingState(Application application) {
        super(application);
        persistence = new SQLitePersistence(application);
        repository = new PackingRepository(persistence);
    }

    @Override protected void onCleared() {
        undo = null;
        persistence.close();
        super.onCleared();
    }

    /** Direct construction keeps release builds independent of reflective constructor retention. */
    public static final class Factory implements ViewModelProvider.Factory {
        private final Application application;

        public Factory(Application application) { this.application = application; }

        @Override public <T extends ViewModel> T create(Class<T> modelClass) {
            if (!PackingState.class.equals(modelClass)) {
                throw new IllegalArgumentException("Unsupported ViewModel");
            }
            return modelClass.cast(new PackingState(application));
        }
    }
}
