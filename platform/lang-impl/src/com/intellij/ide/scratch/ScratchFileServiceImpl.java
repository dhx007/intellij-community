/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.ide.scratch;

import com.intellij.ide.FileIconProvider;
import com.intellij.ide.navigationToolbar.AbstractNavBarModelExtension;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageUtil;
import com.intellij.lang.PerFileMappings;
import com.intellij.lang.PerFileMappingsBase;
import com.intellij.openapi.application.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerAdapter;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider;
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessExtension;
import com.intellij.openapi.fileTypes.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerAdapter;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Iconable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWithId;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.wm.WindowManagerListener;
import com.intellij.psi.LanguageSubstitutor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.ui.UIBundle;
import com.intellij.util.ArrayUtil;
import com.intellij.util.PairConsumer;
import com.intellij.util.PathUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.messages.MessageBus;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;


@State(
  name = "ScratchFileService",
  storages = {
    @Storage(file = StoragePathMacros.APP_CONFIG + "/scratches.xml")
  })
public class ScratchFileServiceImpl extends ScratchFileService implements PersistentStateComponent<Element>{

  private static final RootType NULL_TYPE = new RootType("", null) {};

  private final LightDirectoryIndex<RootType> myIndex;
  private final MyLanguages myScratchMapping = new MyLanguages();

  protected ScratchFileServiceImpl(WindowManager windowManager, MessageBus messageBus) {
    myIndex = new LightDirectoryIndex<RootType>(messageBus.connect(), NULL_TYPE) {

      @Override
      protected void collectRoots(@NotNull PairConsumer<VirtualFile, RootType> consumer) {
        LocalFileSystem fileSystem = LocalFileSystem.getInstance();
        for (RootType r : RootType.getAllRootIds()) {
          String root = getRootPath(r);
          VirtualFile rootFile = fileSystem.findFileByPath(root);
          if (rootFile != null) {
            consumer.consume(rootFile, r);
          }
        }
      }
    };
    initScratchWidget(windowManager);
    initFileOpenedListener(messageBus);
  }

  @NotNull
  @Override
  public String getRootPath(@NotNull RootType rootId) {
    return getRootPath() + "/" + rootId.getId();
  }

  @Nullable
  @Override
  public RootType getRootType(@NotNull VirtualFile file) {
    VirtualFile directory = file.isDirectory() ? file : file.getParent();
    if (!(directory instanceof VirtualFileWithId)) return null;
    RootType result = myIndex.getInfoForFile(directory);
    return result == NULL_TYPE ? null : result;
  }

  private void initFileOpenedListener(MessageBus messageBus) {
    final FileEditorManagerAdapter editorListener = new FileEditorManagerAdapter() {
      @Override
      public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        RootType rootType = getRootType(file);
        if (rootType != null) {
          rootType.fileOpened(file, source);
        }
      }
    };
    ProjectManagerAdapter projectListener = new ProjectManagerAdapter() {
      @Override
      public void projectOpened(Project project) {
        project.getMessageBus().connect(project).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, editorListener);
        FileEditorManager editorManager = FileEditorManager.getInstance(project);
        for (VirtualFile virtualFile : editorManager.getOpenFiles()) {
          editorListener.fileOpened(editorManager, virtualFile);
        }
      }
    };
    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
      projectListener.projectOpened(project);
    }
    messageBus.connect().subscribe(ProjectManager.TOPIC, projectListener);
  }

  private static void initScratchWidget(WindowManager windowManager) {
    final WindowManagerListener windowListener = new WindowManagerListener() {
      @Override
      public void frameCreated(IdeFrame frame) {
        Project project = frame.getProject();
        StatusBar statusBar = frame.getStatusBar();
        if (project == null || statusBar == null || statusBar.getWidget(ScratchWidget.WIDGET_ID) != null) return;
        ScratchWidget widget = new ScratchWidget(project);
        statusBar.addWidget(widget, "before Encoding", project);
        statusBar.updateWidget(ScratchWidget.WIDGET_ID);
      }

      @Override
      public void beforeFrameReleased(IdeFrame frame) {
      }
    };
    for (IdeFrame frame : windowManager.getAllProjectFrames()) {
      windowListener.frameCreated(frame);
    }
    windowManager.addListener(windowListener);
  }

  @NotNull
  protected String getRootPath() {
    return FileUtil.toSystemIndependentName(PathManager.getConfigPath());
  }

  @NotNull
  @Override
  public PerFileMappings<Language> getScratchesMapping() {
    return myScratchMapping;
  }

  @Nullable
  @Override
  public Element getState() {
    return myScratchMapping.getState();
  }

  @Override
  public void loadState(Element state) {
    myScratchMapping.loadState(state);
  }

  private static class MyLanguages extends PerFileMappingsBase<Language> {
    @Override
    protected List<Language> getAvailableValues() {
      return LanguageUtil.getFileLanguages();
    }

    @Nullable
    @Override
    protected String serialize(Language language) {
      return language.getID();
    }

    @Nullable
    @Override
    protected Language handleUnknownMapping(VirtualFile file, String value) {
      return PlainTextLanguage.INSTANCE;
    }

    @Nullable
    @Override
    public Language getMapping(@Nullable VirtualFile file) {
      Language language = super.getMapping(file);
      if (language == null && file != null && file.getFileType() == ScratchFileType.INSTANCE) {
        String extension = file.getExtension();
        FileType fileType = extension == null ? null : FileTypeManager.getInstance().getFileTypeByExtension(extension);
        language = fileType instanceof LanguageFileType ? ((LanguageFileType)fileType).getLanguage() : null;
      }
      return language;
    }
  }

  public static class TypeFactory extends FileTypeFactory {

    @Override
    public void createFileTypes(@NotNull FileTypeConsumer consumer) {
      consumer.consume(ScratchFileType.INSTANCE);
    }
  }

  public static class Substitutor extends LanguageSubstitutor {
    @Nullable
    @Override
    public Language getLanguage(@NotNull VirtualFile file, @NotNull Project project) {
      RootType rootType = ScratchFileService.getInstance().getRootType(file);
      if (rootType == null) return null;
      return rootType.substituteLanguage(project, file);
    }
  }

  public static class Highlighter implements SyntaxHighlighterProvider {
    @Override
    @Nullable
    public SyntaxHighlighter create(@NotNull FileType fileType, @Nullable Project project, @Nullable VirtualFile file) {
      if (project == null || file == null) return null;
      RootType rootType = ScratchFileService.getInstance().getRootType(file);
      if (rootType == null) return null;
      Language language = rootType.substituteLanguage(project, file);
      return language == null ? null : SyntaxHighlighterFactory.getSyntaxHighlighter(language, project, file);
    }
  }

  public static class FilePresentation implements FileIconProvider, EditorTabTitleProvider {

    @Nullable
    @Override
    public Icon getIcon(@NotNull VirtualFile file, @Iconable.IconFlags int flags, @Nullable Project project) {
      if (project == null || file.isDirectory()) return null;
      RootType rootType = ScratchFileService.getInstance().getRootType(file);
      if (rootType == null) return null;
      return rootType.substituteIcon(project, file);
    }

    @Nullable
    @Override
    public String getEditorTabTitle(@NotNull Project project, @NotNull VirtualFile file) {
      RootType rootType = ScratchFileService.getInstance().getRootType(file);
      if (rootType == null) return null;
      return rootType.substituteName(project, file);
    }
  }

  public static class AccessExtension implements NonProjectFileWritingAccessExtension {

    @Override
    public boolean isWritable(@NotNull VirtualFile file) {
      return file.getFileType() == ScratchFileType.INSTANCE;
    }
  }

  public static class NavBarExtension extends AbstractNavBarModelExtension {

    @Nullable
    @Override
    public String getPresentableText(Object object) {
      if (!(object instanceof PsiElement)) return null;
      Project project = ((PsiElement)object).getProject();
      VirtualFile virtualFile = PsiUtilCore.getVirtualFile((PsiElement)object);
      if (virtualFile == null || !virtualFile.isValid()) return null;
      RootType rootType = ScratchFileService.getInstance().getRootType(virtualFile);
      if (rootType == null) return null;
      if (virtualFile.isDirectory()) {
        return additionalRoots(project).contains(virtualFile) ? rootType.getDisplayName() : null;
      }
      return rootType.substituteName(project, virtualFile);
    }

    @NotNull
    @Override
    public Collection<VirtualFile> additionalRoots(Project project) {
      Set<VirtualFile> result = ContainerUtil.newLinkedHashSet();
      LocalFileSystem fileSystem = LocalFileSystem.getInstance();
      ScratchFileService app = ScratchFileService.getInstance();
      for (RootType r : RootType.getAllRootIds()) {
        ContainerUtil.addIfNotNull(result, fileSystem.findFileByPath(app.getRootPath(r)));
      }
      return result;
    }
  }


  @Nullable
  @Override
  public VirtualFile createScratchFile(@NotNull Project project, @NotNull final Language language, @NotNull final String initialContent) {
    final String fileName = "scratch";
    RunResult<VirtualFile> result =
      new WriteCommandAction<VirtualFile>(project, UIBundle.message("file.chooser.create.new.file.command.name")) {
        @Override
        protected void run(@NotNull Result<VirtualFile> result) throws Throwable {
          VirtualFile dir = VfsUtil.createDirectories(getRootPath(ScratchRootType.getInstance()));
          VirtualFile file = VfsUtil.createChildSequent(LocalFileSystem.getInstance(), dir, fileName, "");
          getScratchesMapping().setMapping(file, language);
          VfsUtil.saveText(file, initialContent);
          result.setResult(file);
        }
      }.execute();
    if (result.hasException()) {
      Messages.showMessageDialog(UIBundle.message("create.new.file.could.not.create.file.error.message", fileName),
                                 UIBundle.message("error.dialog.title"), Messages.getErrorIcon());
      return null;
    }
    updateHistory(project, language);
    return result.getResultObject();
  }

  @Override
  public VirtualFile findFile(@NotNull final RootType rootType, @NotNull final String pathName, Option option) throws IOException {
    String fullPath = getRootPath(rootType) + "/" + pathName;
    if (option != Option.create_new_always) {
      VirtualFile file = LocalFileSystem.getInstance().findFileByPath(fullPath);
      if (file != null && !file.isDirectory()) return file;
      if (option == Option.existing_only) return null;
    }
    String ext = PathUtil.getFileExtension(pathName);
    String fileNameExt = PathUtil.getFileName(pathName);
    String fileName = StringUtil.trimEnd(fileNameExt, ext == null ? "" : "." + ext);
    AccessToken token = ApplicationManager.getApplication().acquireWriteActionLock(getClass());
    try {
      VirtualFile dir = VfsUtil.createDirectories(PathUtil.getParentPath(fullPath));
      if (option == Option.create_new_always) {
        return VfsUtil.createChildSequent(LocalFileSystem.getInstance(), dir, fileName, StringUtil.notNullize(ext));
      }
      else {
        return dir.createChildData(LocalFileSystem.getInstance(), fileNameExt);
      }
    }
    finally {
      token.finish();
    }
  }

  private static void updateHistory(@NotNull Project project, @NotNull Language language) {
    String[] values = PropertiesComponent.getInstance(project).getValues(ScratchFileService.class.getName());
    List<String> lastUsed = ContainerUtil.newArrayListWithCapacity(5);
    lastUsed.add(language.getID());
    if (values != null) {
      for (String value : values) {
        if (!lastUsed.contains(value)) {
          lastUsed.add(value);
        }
        if (lastUsed.size() == 5) break;
      }
    }
    PropertiesComponent.getInstance(project).setValues(ScratchFileService.class.getName(), ArrayUtil.toStringArray(lastUsed));
  }
}