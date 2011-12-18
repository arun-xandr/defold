package com.dynamo.cr.sceneed.ui;

import com.dynamo.cr.sceneed.core.Manipulator;
import com.dynamo.cr.sceneed.core.Node;

public class SelectManipulator extends RootManipulator {

    public SelectManipulator() {
    }

    @Override
    public boolean match(Object[] selection) {
        for (Object object : selection) {
            if (object instanceof Node) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void transformChanged() {
    }

    @Override
    public void manipulatorChanged(Manipulator manipulator) {
    }

    @Override
    protected void selectionChanged() {
    }

    @Override
    public void refresh() {
    }

}
