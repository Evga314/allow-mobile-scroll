package ru.evga314.dragscroll.access;

/**
 * Protected members of AbstractScrollArea, exposed by AbstractScrollAreaMixin.
 * Every AbstractScrollArea implements this interface at runtime.
 */
public interface ScrollAreaAccess {
    boolean dragscroll$isOverScrollbar(double x, double y);

    /** Pixels one mouse-wheel notch scrolls this area. */
    double dragscroll$scrollRate();

    /** The vanilla scroll bar thumb is being dragged. */
    boolean dragscroll$isScrolling();

    int dragscroll$scrollerHeight();
}
