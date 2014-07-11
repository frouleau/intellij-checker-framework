package com.jetbrains.plugins.checkerframework.configurable;

import com.intellij.compiler.CompilerConfiguration;
import com.intellij.compiler.CompilerConfigurationImpl;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.util.containers.MultiMap;
import org.checkerframework.javacutil.AbstractTypeProcessor;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.java.compiler.ProcessorConfigProfile;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableModel;
import java.util.ArrayList;
import java.util.List;

public class CheckerFrameworkConfigurable implements Configurable {

    private final MultiMap<ProcessorConfigProfile, String> myToBeAddedProcessors = new MultiMap<ProcessorConfigProfile, String>();
    private final MultiMap<ProcessorConfigProfile, String> myTobeRemovedProcessors = new MultiMap<ProcessorConfigProfile, String>();

    private final ComboBoxModel myProfilesModel;
    private CheckerFrameworkConfigurableUI myUI;

    public CheckerFrameworkConfigurable(final Project project) {
        myProfilesModel = new ProcessorProfileComboboxModel(project);
    }

    @Nls
    @Override
    public String getDisplayName() {
        return "Checker Framework";
    }

    @Nullable
    @Override
    public String getHelpTopic() {
        return null;
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        return getUI().getRoot();
    }

    @Override
    public boolean isModified() {
        return !(myToBeAddedProcessors.isEmpty() && myTobeRemovedProcessors.isEmpty());
    }

    @Override
    public void apply() throws ConfigurationException {
        for (final ProcessorConfigProfile profile : myToBeAddedProcessors.keySet()) {
            for (final String checkerClass : myToBeAddedProcessors.get(profile)) {
                profile.addProcessor(checkerClass);
            }
        }
        for (final ProcessorConfigProfile profile : myTobeRemovedProcessors.keySet()) {
            for (final String checkerClass : myTobeRemovedProcessors.get(profile)) {
                profile.removeProcessor(checkerClass);
            }
        }
        reset();
    }

    @Override
    public void reset() {
        myToBeAddedProcessors.clear();
        myTobeRemovedProcessors.clear();
    }

    @Override
    public void disposeUIResources() {
        // nothing to do here
    }

    private CheckerFrameworkConfigurableUI getUI() {
        if (myUI == null) {
            final TableModel myCheckersModel = new CheckersTableModel();
            myUI = new CheckerFrameworkConfigurableUI() {
                @Override
                protected TableModel getCheckersModel() {
                    return myCheckersModel;
                }

                @Override
                protected ComboBoxModel getProfilesModel() {
                    return myProfilesModel;
                }
            };
        }
        return myUI;
    }

    private class CheckersTableModel extends AbstractTableModel {

        private final String[] myColumnNames = {"Enabled/Disabled", "Checker class"};

        @Override
        public int getColumnCount() {
            return myColumnNames.length;
        }

        @Override
        public String getColumnName(int col) {
            return myColumnNames[col];
        }

        @Override
        public int getRowCount() {
            return CheckerFrameworkSettings.BUILTIN_CHECKERS.size();
        }

        @Override
        public Object getValueAt(int row, int col) {
            final Class clazz = CheckerFrameworkSettings.BUILTIN_CHECKERS.get(row);
            final String canonicalName = clazz.getCanonicalName();
            final ProcessorConfigProfile currentProfile = getUI().getCurrentSelectedProfile();
            return col == 0
                   ? (
                         currentProfile.getProcessors().contains(canonicalName) || (
                             myToBeAddedProcessors.containsKey(currentProfile)
                             && myToBeAddedProcessors.get(currentProfile).contains(canonicalName)
                         )
                     ) && (
                         !myTobeRemovedProcessors.containsKey(currentProfile)
                         || !myTobeRemovedProcessors.get(currentProfile).contains(canonicalName)
                     )
                   : clazz;
        }

        @Override
        public Class getColumnClass(int c) {
            switch (c) {
                case 0:
                    return Boolean.class;
                default:
                    return Class.class;
            }
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col == 0;
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            if (value.equals(getValueAt(row, col))) return;

            final Class<? extends AbstractTypeProcessor> clazz = CheckerFrameworkSettings.BUILTIN_CHECKERS.get(row);

            final MultiMap<ProcessorConfigProfile, String> mapToAddTo = Boolean.TRUE.equals(value)
                                                                        ? myToBeAddedProcessors
                                                                        : myTobeRemovedProcessors;
            final MultiMap<ProcessorConfigProfile, String> mapToRemoveFrom = Boolean.TRUE.equals(value)
                                                                             ? myTobeRemovedProcessors
                                                                             : myToBeAddedProcessors;
            final ProcessorConfigProfile currentProfile = getUI().getCurrentSelectedProfile();

            if (!mapToRemoveFrom.get(currentProfile).remove(clazz.getCanonicalName())) {
                mapToAddTo.putValue(currentProfile, clazz.getCanonicalName());
            }

            fireTableCellUpdated(row, col);
        }
    }

    private static class ProcessorProfileComboboxModel extends DefaultComboBoxModel {
        private final List<ProcessorConfigProfile> myConfigProfiles = new ArrayList<ProcessorConfigProfile>();

        private ProcessorProfileComboboxModel(Project project) {
            final CompilerConfigurationImpl compilerConfiguration = (CompilerConfigurationImpl)CompilerConfiguration.getInstance(project);
            myConfigProfiles.add(compilerConfiguration.getDefaultProcessorProfile());
            myConfigProfiles.addAll(compilerConfiguration.getModuleProcessorProfiles());
        }

        @Override
        public Object getElementAt(int index) {
            return myConfigProfiles.get(index);
        }

        @Override
        public int getSize() {
            return myConfigProfiles.size();
        }
    }
}
