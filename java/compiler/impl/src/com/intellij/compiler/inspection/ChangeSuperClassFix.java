/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package com.intellij.compiler.inspection;

import com.intellij.codeInsight.FileModificationService;
import com.intellij.codeInsight.daemon.GroupNames;
import com.intellij.codeInsight.intention.HighPriorityAction;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogBuilder;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.refactoring.ui.MemberSelectionPanel;
import com.intellij.refactoring.util.classMembers.MemberInfo;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ChangeSuperClassFix implements LocalQuickFix, HighPriorityAction {
  @NotNull
  private final SmartPsiElementPointer<PsiClass> myNewSuperClass;
  @NotNull
  private final SmartPsiElementPointer<PsiClass> myOldSuperClass;
  private final int myInheritorCount;
  @NotNull
  private final String myNewSuperName;
  private final boolean myImplements;

  public ChangeSuperClassFix(@NotNull final PsiClass newSuperClass,
                             @NotNull final PsiClass oldSuperClass,
                             final int percent,
                             final boolean isImplements) {
    final SmartPointerManager smartPointerManager = SmartPointerManager.getInstance(newSuperClass.getProject());
    myNewSuperName = ObjectUtils.notNull(newSuperClass.getQualifiedName());
    myNewSuperClass = smartPointerManager.createSmartPsiElementPointer(newSuperClass);
    myOldSuperClass = smartPointerManager.createSmartPsiElementPointer(oldSuperClass);
    myInheritorCount = percent;
    myImplements = isImplements;
  }

  @NotNull
  @TestOnly
  public PsiClass getNewSuperClass() {
    return ObjectUtils.notNull(myNewSuperClass.getElement());
  }

  @TestOnly
  public int getInheritorCount() {
    return myInheritorCount;
  }

  @NotNull
  @Override
  public String getName() {
    return String.format("Make " + (myImplements ? "implements" : "extends") + " '%s'", myNewSuperName);
  }

  @NotNull
  @Override
  public String getFamilyName() {
    return GroupNames.INHERITANCE_GROUP_NAME;
  }

  @Override
  public boolean startInWriteAction() {
    return false;
  }

  @Override
  public void applyFix(@NotNull final Project project, @NotNull final ProblemDescriptor problemDescriptor) {
    final PsiClass oldSuperClass = myOldSuperClass.getElement();
    final PsiClass newSuperClass = myNewSuperClass.getElement();
    if (oldSuperClass == null || newSuperClass == null) return;
    PsiElement element = problemDescriptor.getPsiElement();
    if (!(element instanceof PsiClass) || !FileModificationService.getInstance().preparePsiElementsForWrite(element)) return;
    PsiClass aClass = (PsiClass)element;
    changeSuperClass(aClass, oldSuperClass, newSuperClass);
  }

  /**
   * oldSuperClass and newSuperClass can be interfaces or classes in any combination
   * <p/>
   * 1. not checks that oldSuperClass is really super of aClass
   * 2. not checks that newSuperClass not exists in currently existed supers
   */
  private static void changeSuperClass(@NotNull final PsiClass aClass,
                                       @NotNull final PsiClass oldSuperClass,
                                       @NotNull final PsiClass newSuperClass) {
    List<PsiMethod> ownMethods = ((PsiExtensibleClass)aClass).getOwnMethods();
    // first is own method, second is parent
    List<Pair<PsiMethod, Set<PsiMethod>>> oldOverridenMethods =
      ownMethods.stream().map(m -> {
        if (m.isConstructor()) return null;
        PsiMethod[] supers = m.findSuperMethods(oldSuperClass);
        if (supers.length == 0) return null;
        return Pair.create(m, ContainerUtil.set(supers));
      }).collect(Collectors.toList());

    JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(aClass.getProject());
    PsiElementFactory factory = psiFacade.getElementFactory();
    WriteAction.run(() -> {
      PsiElement ref;
      if (aClass instanceof PsiAnonymousClass) {
        ref = ((PsiAnonymousClass)aClass).getBaseClassReference().replace(factory.createClassReferenceElement(newSuperClass));
      } else {
        PsiReferenceList extendsList = ObjectUtils.notNull(aClass.getExtendsList());
        PsiJavaCodeReferenceElement[] refElements =
          ArrayUtil.mergeArrays(getReferences(extendsList), getReferences(aClass.getImplementsList()));
        for (PsiJavaCodeReferenceElement refElement : refElements) {
          if (refElement.isReferenceTo(oldSuperClass)) {
            refElement.delete();
          }
        }

        PsiReferenceList list;
        if (newSuperClass.isInterface() && !aClass.isInterface()) {
          list = aClass.getImplementsList();
        }
        else {
          list = extendsList;
          PsiJavaCodeReferenceElement[] elements = list.getReferenceElements();
          if (elements.length == 1 &&
              elements[0].isReferenceTo(psiFacade.findClass(CommonClassNames.JAVA_LANG_OBJECT, aClass.getResolveScope()))) {
            elements[0].delete();
          }
        }
        assert list != null;
        ref = list.add(factory.createClassReferenceElement(newSuperClass));
      }
      JavaCodeStyleManager.getInstance(aClass.getProject()).shortenClassReferences(ref);
    });

    if (ownMethods.isEmpty()) {
      // should not override methods from a new super class
      return;
    }
    Stream<PsiMethod> memberInfos = oldOverridenMethods.stream().filter(m -> {
      Set<PsiMethod> newSupers = ContainerUtil.set(m.getFirst().findSuperMethods(newSuperClass));
      return !newSupers.equals(m.getSecond());
    }).map(m -> m.getFirst());

    List<PsiMethod> toDelete = getOverridenMethodsToDelete(memberInfos, newSuperClass.getName());
    WriteAction.run(() -> {
      for (PsiMethod method : toDelete) {
        method.delete();
      }
    });
  }

  @NotNull
  private static PsiJavaCodeReferenceElement[] getReferences(PsiReferenceList list) {
    return list == null ? PsiJavaCodeReferenceElement.EMPTY_ARRAY : list.getReferenceElements();
  }

  @NotNull
  private static List<PsiMethod> getOverridenMethodsToDelete(Stream<PsiMethod> candidates, String newClassName) {
    DialogBuilder dlg = new DialogBuilder();
    MemberSelectionPanel panel = new MemberSelectionPanel("<html>Choose members to delete since they are already defined in <b>" + newClassName + "</b>",
                                                          candidates.map(m -> {
      MemberInfo info = new MemberInfo(m);
      info.setChecked(true);
      return info;
    }).collect(Collectors.toList()), null);
    dlg.setCenterPanel(panel);
    dlg.setTitle("Choose Members");
    return dlg.showAndGet()
           ? panel.getTable().getSelectedMemberInfos().stream().map(info -> (PsiMethod)info.getMember()).collect(Collectors.toList())
           : Collections.emptyList();
  }
}
