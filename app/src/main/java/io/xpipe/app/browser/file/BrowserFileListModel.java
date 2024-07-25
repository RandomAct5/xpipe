package io.xpipe.app.browser.file;

import io.xpipe.app.browser.fs.OpenFileSystemModel;
import io.xpipe.app.issue.ErrorEvent;
import io.xpipe.core.process.OsType;
import io.xpipe.core.store.FileKind;
import io.xpipe.core.store.FileNames;
import io.xpipe.core.store.FileSystem;

import javafx.beans.property.Property;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Getter
public final class BrowserFileListModel {

    static final Comparator<BrowserEntry> FILE_TYPE_COMPARATOR =
            Comparator.comparing(path -> path.getRawFileEntry().resolved().getKind() != FileKind.DIRECTORY);

    private final OpenFileSystemModel.SelectionMode selectionMode;

    private final OpenFileSystemModel fileSystemModel;
    private final Property<Comparator<BrowserEntry>> comparatorProperty =
            new SimpleObjectProperty<>(FILE_TYPE_COMPARATOR);
    private final Property<List<BrowserEntry>> all = new SimpleObjectProperty<>(new ArrayList<>());
    private final Property<List<BrowserEntry>> shown = new SimpleObjectProperty<>(new ArrayList<>());
    private final ObservableList<BrowserEntry> previousSelection = FXCollections.observableArrayList();
    private final ObservableList<BrowserEntry> selection = FXCollections.observableArrayList();

    private final Property<BrowserEntry> draggedOverDirectory = new SimpleObjectProperty<>();
    private final Property<Boolean> draggedOverEmpty = new SimpleBooleanProperty();
    private final Property<BrowserEntry> editing = new SimpleObjectProperty<>();

    public BrowserFileListModel(OpenFileSystemModel.SelectionMode selectionMode, OpenFileSystemModel fileSystemModel) {
        this.selectionMode = selectionMode;
        this.fileSystemModel = fileSystemModel;

        fileSystemModel.getFilter().addListener((observable, oldValue, newValue) -> {
            refreshShown();
        });

        selection.addListener((ListChangeListener<? super BrowserEntry>) c -> {
            previousSelection.setAll(c.getList());
        });
    }

    public void setAll(Stream<FileSystem.FileEntry> newFiles) {
        try (var s = newFiles) {
            var l = s.filter(entry -> entry != null)
                    .map(entry -> new BrowserEntry(entry, this))
                    .toList();
            all.setValue(l);
            refreshShown();
        }
    }

    public void setComparator(Comparator<BrowserEntry> comparator) {
        comparatorProperty.setValue(comparator);
        refreshShown();
    }

    private void refreshShown() {
        List<BrowserEntry> filtered = fileSystemModel.getFilter().getValue() != null
                ? all.getValue().stream()
                        .filter(entry -> {
                            var name = FileNames.getFileName(
                                            entry.getRawFileEntry().getPath())
                                    .toLowerCase(Locale.ROOT);
                            var filterString =
                                    fileSystemModel.getFilter().getValue().toLowerCase(Locale.ROOT);
                            return name.contains(filterString);
                        })
                        .toList()
                : all.getValue();

        var listCopy = new ArrayList<>(filtered);
        listCopy.sort(order());
        shown.setValue(listCopy);
    }

    public Comparator<BrowserEntry> order() {
        var dirsFirst = Comparator.<BrowserEntry, Boolean>comparing(
                path -> path.getRawFileEntry().resolved().getKind() != FileKind.DIRECTORY);
        var comp = comparatorProperty.getValue();

        Comparator<BrowserEntry> us = comp != null ? dirsFirst.thenComparing(comp) : dirsFirst;
        return us;
    }

    public BrowserEntry rename(BrowserEntry old, String newName) {
        var fullPath = FileNames.join(fileSystemModel.getCurrentPath().get(), old.getFileName());
        var newFullPath = FileNames.join(fileSystemModel.getCurrentPath().get(), newName);

        // This check will fail on case-insensitive file systems when changing the case of the file
        // So skip it in this case
        var skipExistCheck =
                fileSystemModel.getFileSystem().getShell().orElseThrow().getOsType() == OsType.WINDOWS
                        && old.getFileName().equalsIgnoreCase(newName);
        if (!skipExistCheck) {
            boolean exists;
            try {
                exists = fileSystemModel.getFileSystem().fileExists(newFullPath)
                        || fileSystemModel.getFileSystem().directoryExists(newFullPath);
            } catch (Exception e) {
                ErrorEvent.fromThrowable(e).handle();
                return old;
            }

            if (exists) {
                ErrorEvent.fromMessage("Target " + newFullPath + " does already exist")
                        .expected()
                        .handle();
                fileSystemModel.refresh();
                return old;
            }
        }

        try {
            fileSystemModel.getFileSystem().move(fullPath, newFullPath);
            fileSystemModel.refresh();
            var b = all.getValue().stream()
                    .filter(browserEntry ->
                            browserEntry.getRawFileEntry().getPath().equals(newFullPath))
                    .findFirst()
                    .orElse(old);
            return b;
        } catch (Exception e) {
            ErrorEvent.fromThrowable(e).handle();
            return old;
        }
    }

    public void onDoubleClick(BrowserEntry entry) {
        if (entry.getRawFileEntry().resolved().getKind() == FileKind.DIRECTORY) {
            fileSystemModel.cdAsync(entry.getRawFileEntry().resolved().getPath());
        }
    }
}
