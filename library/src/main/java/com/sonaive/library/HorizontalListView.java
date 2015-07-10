package com.sonaive.library;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.EdgeEffectCompat;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ListAdapter;
import android.widget.Scroller;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Created by liutao on 15-7-9.
 */
public class HorizontalListView extends AbsListView {

    private static final String TAG = HorizontalListView.class.getSimpleName();

    /** Defines where to insert items into the ViewGroup, as defined in {@code ViewGroup #addViewInLayout(View, int, LayoutParams, boolean)} */
    private static final int INSERT_AT_END_OF_LIST = -1;
    private static final int INSERT_AT_START_OF_LIST = 0;

    /** The velocity to use for over scroll absorption */
    private static final float FLING_DEFAULT_ABSORB_VELOCITY = 30f;

    /** The friction amount to use for the fling tracker */
    private static final float FLING_FRICTION = 0.009f;

    /** Tracks ongoing flings */
    private Scroller mFlingTracker = new Scroller(getContext());

    /** Holds a reference to the adapter bounds to this view */
    private ListAdapter mAdapter;

    /** Holds a cache of recycled views to be reused as needed */
    private List<Queue<View>> mRemovedViewsCache = new ArrayList<>();

    /** The x position of the currently rendered view */
    protected int mCurrentX;

    /** The x position of the next to be rendered view */
    protected int mNextX;

    /** Tracks the starting layout position of the leftmost view */
    private int mDisplayOffset;

    /** The adapter index of the leftmost view currently visible */
    private int mLeftViewAdapterIndex;

    /** The adapter index of the rightmost view currently visible */
    private int mRightViewAdapterIndex;

    /** Tracks the currently selected accessibility item */
    private int mCurrentlySelectedAdapterIndex;

    /** Tracks the maximum possible X position, stays at max value until last item is laid out and it can be determined */
    private int mMaxX = Integer.MAX_VALUE;

    /** Gesture listener to receive callback when gestures are detected */
    private final GestureListener mGestureListener = new GestureListener();

    /** Used for detecting gestures within this view so they can be handled */
    private GestureDetector mGestureDetector;

    /** Tracks the state of left edge glow */
    private EdgeEffectCompat mEdgeGlowLeft;

    /** Tracks the state of right edge glow */
    private EdgeEffectCompat mEdgeGlowRight;

    /** The height measure specific for this view, used to help size the child views */
    private int mHeightMeasureSpec;

    /** Flag used to mark the adapter data has changed so the view can be relaid out */
    private boolean mDataChanged;

    /** Callback interface to be invoked when scroll state has changed */
    private OnScrollStateChangedListener mOnScrollStateChangedListener = null;

    /***
     * Represent current scroll state. Needed when scroll state has changed so scroll listener can notify a change.
     */
    private OnScrollStateChangedListener.ScrollState mCurrentScrollState = OnScrollStateChangedListener.ScrollState.SCROLL_STATE_IDLE;

    public HorizontalListView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mEdgeGlowLeft = new EdgeEffectCompat(context);
        mEdgeGlowRight = new EdgeEffectCompat(context);
        mGestureDetector = new GestureDetector(context, mGestureListener);
        bindGestureDetector();
        initView();
        setWillNotDraw(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            HoneyCombPlus.setFriction(mFlingTracker, FLING_FRICTION);
        }
    }

    /**
     * Register gesture detector to receive gesture notifications for this view.
     */
    private void bindGestureDetector() {
        // Generic touch listener that can be applied to any view that needs to process gestures.
        final OnTouchListener gestureListenerHandler = new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // Delegate the touch event to our gesture detector. bnvhg
                return mGestureDetector.onTouchEvent(event);
            }
        };
        setOnTouchListener(gestureListenerHandler);
    }

    private void initView() {
        mCurrentX = 0;
        mNextX = 0;
        mDisplayOffset = 0;
        mLeftViewAdapterIndex = -1;
        mRightViewAdapterIndex = -1;
        mMaxX = Integer.MAX_VALUE;
        setCurrentScrollState(OnScrollStateChangedListener.ScrollState.SCROLL_STATE_IDLE);
    }

    @Override
    public void setSelection(int position) {
        mCurrentlySelectedAdapterIndex = position;
    }

    @Override
    public ListAdapter getAdapter() {
        return mAdapter;
    }

    @Override
    public void setAdapter(ListAdapter adapter) {
        if (mAdapter != null) {
            mAdapter.unregisterDataSetObserver(null);
        }

        if (adapter != null) {
            mAdapter = adapter;
            mAdapter.registerDataSetObserver(null);
            initializeRemovedViewCache(mAdapter.getViewTypeCount());
        }
        reset();
    }

    /**
     * Will create and initialize a cache for the given number of different type of views
     * @param viewTypeCount The total number of different views supported
     */
    private void initializeRemovedViewCache(int viewTypeCount) {
        // The cache is created such that the response from mAdapter.getItemViewType is the array index to the correct cache for that item.
        mRemovedViewsCache.clear();
        for (int i = 0; i < viewTypeCount; i++) {
            mRemovedViewsCache.add(new LinkedList<View>());
        }
    }

    /** Will re-initialize the HorizontalListView to remove all the child views rendered and reset to initial configuration */
    private void reset() {
        initView();
        removeAllViewsInLayout();
        requestLayout();
    }

    /** Used to schedule a request layout via a runnable */
    private Runnable mDelayedLayout = new Runnable() {
        @Override
        public void run() {
            requestLayout();
        }
    };

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // Cache off the measure spec
        mHeightMeasureSpec = heightMeasureSpec;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        if (mAdapter == null) {
            return;
        }

        if (mDataChanged) {
            int oldCurrentX = mCurrentX;
            initView();
            removeAllViewsInLayout();
            mNextX = oldCurrentX;
            mDataChanged = false;
        }

        // If in a fling
        if (mFlingTracker.computeScrollOffset()) {
            // Compute the next position
            mNextX = mFlingTracker.getCurrX();
        }

        // Prevent from scrolling past 0 so you can not scroll past the end of list to the left
        if (mNextX < 0) {
            mNextX = 0;

            // Show an edge effect absorbing the current velocity
            if (mEdgeGlowLeft.isFinished()) {
                mEdgeGlowLeft.onAbsorb((int) determineFlingAbsorbVelocity());
            }
            mFlingTracker.forceFinished(true);
            setCurrentScrollState(OnScrollStateChangedListener.ScrollState.SCROLL_STATE_IDLE);
        }

        if (mNextX > mMaxX) {
            mNextX = mMaxX;

            if (mEdgeGlowRight.isFinished()) {
                mEdgeGlowRight.onAbsorb((int) determineFlingAbsorbVelocity());
            }
            mFlingTracker.forceFinished(true);
            setCurrentScrollState(OnScrollStateChangedListener.ScrollState.SCROLL_STATE_IDLE);
        }

        int dx = mCurrentX - mNextX;
        removeNonVisibleChildren(dx);
        fillList(dx);
        positionChildren(dx);

        // Since the view has now been drawn, update the mCurrentX
        mCurrentX = mNextX;

        if (mFlingTracker.isFinished()) {
            // If fling just ended
            if (mCurrentScrollState == OnScrollStateChangedListener.ScrollState.SCROLL_STATE_FLING) {
                setCurrentScrollState(OnScrollStateChangedListener.ScrollState.SCROLL_STATE_IDLE);
            }
        } else {
            ViewCompat.postOnAnimation(this, mDelayedLayout);
        }



    }

    /**
     * Determines the current fling absorb velocity
     */
    private float determineFlingAbsorbVelocity() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            return IceCreamSandwichPlus.getCurrVelocity(mFlingTracker);
        } else {
            // Unable to get the velocity so just return a default.
            // In actuality this is never used since EdgeEffectCompat does not draw anything unless the device is ICS+.
            // Less then ICS EdgeEffectCompat essentially performs a NOP.
            return FLING_DEFAULT_ABSORB_VELOCITY;
        }
    }

    private void removeNonVisibleChildren(final int dx) {
        View child = getLeftmostChild();

        // Loop removing the leftmost child, until that child is bound to on the screen
        while (child.getRight() + dx <= 0) {
            // The child is being completely removed so remove its width from the display offset and its divider if it has one.
            // To remove add the size of the child and its divider (if it has one) to the offset.
            // You need to add since its being removed from the left side, i.e. shifting the offset to the right.
            mDisplayOffset += child.getMeasuredWidth();

            // Recycle the removed view
            recycleView(mLeftViewAdapterIndex, child);

            // Keep track of the adapter index of the leftmost child
            mLeftViewAdapterIndex++;

            // Actually remove the child
            removeViewInLayout(child);

            child = getLeftmostChild();
        }

        child = getRightmostChild();
        // Loop removing the rightmost child, until that child is bound to on the screen
        while (child.getLeft() + dx >= getWidth()) {
            recycleView(mRightViewAdapterIndex, child);
            mRightViewAdapterIndex--;
            removeViewInLayout(child);
            child = getRightmostChild();
        }
    }

    /** Adds child views to the left and right of current view until the screen if full of views */
    private void fillList(final int dx) {
        int edge = 0;
        View child = getRightmostChild();

        if (child != null) {
            edge = child.getRight();
        }
        // Adds child views to the right, until past the edge of the screen
        fillListRight(edge, dx);

        edge = 0;
        child = getLeftmostChild();
        if (child != null) {
            edge = child.getLeft();
        }
        // Adds child views to the left, until past the edge of the screen
        fillListLeft(edge, dx);
    }

    private void fillListLeft(int leftEdge, final int dx) {
        while (leftEdge + dx > 0 && mLeftViewAdapterIndex >= 1) {
            mLeftViewAdapterIndex--;
            View child = mAdapter.getView(mLeftViewAdapterIndex, getRecycledView(mLeftViewAdapterIndex), this);
            addAndMeasureChild(child, INSERT_AT_START_OF_LIST);
            leftEdge -= child.getMeasuredWidth();
            mDisplayOffset -= child.getMeasuredWidth();
        }
    }

    private void fillListRight(int rightEdge, final int dx) {
        while (rightEdge + dx < getWidth() && mRightViewAdapterIndex + 1 < getCount()) {
            mRightViewAdapterIndex++;
            View child = mAdapter.getView(mRightViewAdapterIndex, getRecycledView(mRightViewAdapterIndex), this);
            addAndMeasureChild(child, INSERT_AT_END_OF_LIST);
            rightEdge += child.getMeasuredWidth();
        }

    }

    private void positionChildren(final int dx) {
        int childCount = getChildCount();
        int leftOffset = mDisplayOffset;
        mDisplayOffset += dx;
        // Loop each child view
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            int left = leftOffset + getPaddingLeft();
            int top = getPaddingTop();
            int right = left + child.getMeasuredWidth();
            int bottom = top + child.getMeasuredHeight();
            // Layout the child
            child.layout(left, top, right, bottom);

            leftOffset += child.getMeasuredWidth();
        }
    }

    /**
     * Gets the leftmost child that is on the screen
     */
    private View getLeftmostChild() {
        return getChildAt(0);
    }

    /**
     * Gets the rightmost child that is on the screen
     */
    private View getRightmostChild() {
        return getChildAt(getChildCount() - 1);
    }

    private void recycleView(int adapterIndex, View child) {
        int itemViewType = mAdapter.getItemViewType(adapterIndex);
        // There is one Queue of views for each different type of view.
        // Just add the view to the pile of other views of the same type.
        // The order they are added and removed does not matter.

        if (isItemViewTypeValid(itemViewType)) {
            mRemovedViewsCache.get(itemViewType).offer(child);
        }
    }

    private View getRecycledView(int adapterIndex) {
        int itemViewType = mAdapter.getItemViewType(adapterIndex);

        if (isItemViewTypeValid(itemViewType)) {
            return mRemovedViewsCache.get(itemViewType).poll();
        }
        return null;
    }

    private boolean isItemViewTypeValid(int itemViewType) {
        return itemViewType < mRemovedViewsCache.size();
    }

    /** Adds child to this view group and measures it so it renders the right size */
    private void addAndMeasureChild(View child, int viewPos) {
        LayoutParams params = getLayoutParams(child);
        addViewInLayout(child, viewPos, params, true);
        measureChild(child);
    }

    /**
     * Measure the provided child
     */
    private void measureChild(View child) {
        LayoutParams layoutParams = getLayoutParams(child);
        int heightSpec = ViewGroup.getChildMeasureSpec(mHeightMeasureSpec, getPaddingTop() + getPaddingBottom(), layoutParams.height);

        int widthSpec;
        if (layoutParams.width > 0) {
            widthSpec = MeasureSpec.makeMeasureSpec(layoutParams.width, MeasureSpec.EXACTLY);
        } else {
            widthSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        }

        child.measure(widthSpec, heightSpec);
    }

    private LayoutParams getLayoutParams(View child) {
        LayoutParams params = (LayoutParams) child.getLayoutParams();
        if (params == null) {
            params = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        return params;
    }

    class GestureListener extends GestureDetector.SimpleOnGestureListener {

    }

    public interface OnScrollStateChangedListener {
        enum ScrollState {
            /**
             * The view is not scrolling. Note navigating the list using trackball counts as being in the
             * idle state since these transitions are not animated.
             */
            SCROLL_STATE_IDLE,

            /**
             * The user is scrolling using touch, and their fingers are still on the screen.
             */
            SCROLL_STATE_TOUCH_SCROLL,

            /**
             * The user had previously been scrolling using touch and had performed a fling. The animation
             * is now coasting to stop.
             */
            SCROLL_STATE_FLING
        }

        /**
         * Callback method to be invoked when the scroll state changes.
         * @param scrollState The current scroll state
         */
        void onScrollStateChanged(ScrollState scrollState);
    }

    /**
     * Sets a listener to be invoked when the scroll state has changed.
     * @param listener The listener to be invoked
     */
    public void setOnScrollStateChangedListener(OnScrollStateChangedListener listener) {
        mOnScrollStateChangedListener = listener;
    }

    /**
     * Call to set a new state
     * If it has changed and a listener is registered then it will be notified.
     */
    public void setCurrentScrollState(OnScrollStateChangedListener.ScrollState newScrollState) {
        if (newScrollState != mCurrentScrollState && mOnScrollStateChangedListener != null) {
            mOnScrollStateChangedListener.onScrollStateChanged(newScrollState);
        }
        mCurrentScrollState = newScrollState;
    }

    @TargetApi(11)
    /**
     * Wrapper class to protect access to api version is 11 and above.
     */
    private static final class HoneyCombPlus {
        static {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
                throw new RuntimeException("Should not get HoneyCombPlus class unless sdk is >= 11!");
            }
        }

        /**
         * Sets the friction to the provided scroller.
         */
        public static void setFriction(Scroller scroller, float friction) {
            if (scroller != null) {
                scroller.setFriction(friction);
            }
        }
    }

    @TargetApi(14)
    /**
     * Wrapper class to protect access to api version is 14 and above.
     */
    private static final class IceCreamSandwichPlus {
        static {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                throw new RuntimeException("Should not get IceCreamSandwichPlus class unless sdk is >= 14");
            }
        }

        /**
         * Gets the current velocity from the provided scroller.
         */
        public static float getCurrVelocity(Scroller scroller) {
            if (scroller != null) {
                return scroller.getCurrVelocity();
            }
            return 0;
        }
    }
}
