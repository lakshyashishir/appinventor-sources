package com.google.appinventor.components.runtime;

import android.R;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;

import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.annotations.DesignerProperty;
import com.google.appinventor.components.annotations.IsColor;
import com.google.appinventor.components.annotations.PropertyCategory;
import com.google.appinventor.components.annotations.SimpleEvent;
import com.google.appinventor.components.annotations.SimpleObject;
import com.google.appinventor.components.annotations.SimpleProperty;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.common.PropertyTypeConstants;
import com.google.appinventor.components.common.YaVersion;

/**
 * This class is used to display a `Slider`.
 *
 * ![Example Slider icon](images/slider.png)
 *
 * A `Slider` is a progress bar that adds a draggable thumb. You can touch the thumb and drag left
 * or right to set the slider thumb position. As the Slider thumb is dragged, it will trigger the
 * {@link #PositionChanged(float)} event, reporting the position of the `Slider` thumb. The
 * reported position of the thumb can be used to dynamically update another component attribute,
 * such as the {@link TextBox#FontSize(float)} of a `TextBox` or the
 * [Radius](animation.html#Ball.Radius) of a `Ball`.
 *
 * The `Slider` uses the following default values. However these values can be changed through the
 * Designer or Blocks editor:
 *
 *  * {@link #MinValue(float)} = 10
 *  * {@link #MaxValue(float)} = 50
 *  * {@link #ThumbPosition(float)} = 30
 *
 * @author kashi01@gmail.com (M. Hossein Amerkashi)
 * @author hal@mit.edu (Hal Abelson)
 */
@DesignerComponent(version = YaVersion.SLIDER_COMPONENT_VERSION,
    description = "A Slider is a progress bar that adds a draggable thumb. You can touch " +
        "the thumb and drag left or right to set the slider thumb position. " +
        "As the Slider thumb is dragged, it will trigger the PositionChanged event, " +
        "reporting the position of the Slider thumb. The reported position of the " +
        "Slider thumb can be used to dynamically update another component " +
        "attribute, such as the font size of a TextBox or the radius of a Ball.",
    category = ComponentCategory.USERINTERFACE,
    iconName = "images/slider.png")
@SimpleObject
public class Slider extends AndroidViewComponent implements SeekBar.OnSeekBarChangeListener {
  private final static String LOG_TAG = "Slider";
  private static final boolean DEBUG = false;

  private final SeekBar seekbar;

  private int numberOfSteps;
  private boolean notice = true;
  // slider mix, max, and thumb positions
  private float minValue;
  private float maxValue;
  // thumbPosition is a number between minValue and maxValue
  private float thumbPosition;
  private boolean thumbEnabled;

  // colors of the bar after and before the thumb position
  private int rightColor;
  private int leftColor;
  private int thumbColor;

  private final static int initialRightColor = Component.COLOR_GRAY;
  private final static String initialRightColorString = Component.DEFAULT_VALUE_COLOR_GRAY;
  private final static int initialLeftColor = Component.COLOR_ORANGE;
  private final static String initialLeftColorString = Component.DEFAULT_VALUE_COLOR_ORANGE;
  private final static int initialThumbColor = Component.COLOR_DKGRAY;
  private final static String initialThumbColorString = Component.DEFAULT_VALUE_COLOR_DKGRAY;

  // seekbar.getThumb was introduced in API level 16 and the component warns the user
  // that apps using Sliders won't work if the API level is below 16.  But for very old systems the
  // app won't even *load* because the verifier will reject getThumb.  I don't know how old - the
  // rejection happens on Donut but not on Gingerbread.
  // The purpose of SeekBarHelper class is to avoid getting rejected by the Android verifier when the
  // Slider component code is loaded into a device with API level less than Gingerbread.
  // We do this trick by putting the use of getThumb in the class SeekBarHelper and arranging for
  // the class to be compiled only if the API level is at least Gingerbread.  This same trick is
  // used in implementing the Sound component.
  private class SeekBarHelper {
    public void getThumb(int alpha) {
      seekbar.getThumb().mutate().setAlpha(alpha);
    }
  }

  /**
   * Creates a new Slider component.
   *
   * @param container container that the component will be placed in
   */
  public Slider(ComponentContainer container) {
    super(container);
    seekbar = new SeekBar(container.$context());

    if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
      seekbar.setSplitTrack(false);
    }

    leftColor = initialLeftColor;
    rightColor = initialRightColor;
    thumbColor = initialThumbColor;
    setSliderColors();

    // Adds the component to its designated container
    container.$add(this);

    // Initial property values
    minValue = Component.SLIDER_MIN_VALUE;
    maxValue = Component.SLIDER_MAX_VALUE;
    thumbPosition = Component.SLIDER_THUMB_VALUE;
    thumbEnabled = true;
    numberOfSteps = 100;

    seekbar.setOnSeekBarChangeListener(this);

    // We set the maximum range of the slider to numberOfSteps, 
    // obtaining the slider precision exactly as we want.

    seekbar.setMax(numberOfSteps);

    // Based on given minValue, maxValue, and thumbPosition, determine where the seekbar
    // thumb position would be within normal SeekBar 0 - numberOfSteps range
    
    setSeekbarPosition();

    if (DEBUG) {
      Log.d(LOG_TAG, "Slider initial min, max, thumb values are: " +
          MinValue() + "/" + MaxValue() + "/" + ThumbPosition() + "/" + NumberOfSteps());
    }

    if (DEBUG) {
      Log.d(LOG_TAG, "API level is " + VERSION.SDK_INT);
    }

  }

  // NOTE(hal): On old phones, up through 2.2.2 and maybe higher, the color of the bar doesn't
  // change until the thumb is moved.  I'm ignoring that problem.
  private void setSliderColors() {
    if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
      seekbar.setProgressTintList(ColorStateList.valueOf(leftColor));
      seekbar.setThumbTintList(ColorStateList.valueOf(thumbColor));
      if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP_MR1 ||
          !(seekbar.getProgressDrawable() instanceof StateListDrawable)) {
        seekbar.setProgressBackgroundTintList(ColorStateList.valueOf(rightColor));
        //seekbar.setProgressBackgroundTintMode(Mode.MULTIPLY);
      } else {
        // Looking at the AOSP code, the previous calls should effectively accomplish what the
        // following code does... except it doesn't on Android 5.0. Instead, the result is that the
        // right side color is 50% opacity of leftColor. The following code works on Android 5.0,
        // but assumes a Drawable hierarchy that may not be true if the device manufacturer deviates
        // from the AOSP design. If that is the case, then the right hand side will not change.
        StateListDrawable drawable = (StateListDrawable) seekbar.getProgressDrawable();
        if (drawable.getCurrent() instanceof LayerDrawable) {
          LayerDrawable layerDrawable = (LayerDrawable) drawable.getCurrent();
          Drawable background = layerDrawable.findDrawableByLayerId(R.id.background);
          background.setTintList(ColorStateList.valueOf(rightColor));
          background.setTintMode(Mode.MULTIPLY);
        }
      }
    } else {
      LayerDrawable fullBar = (LayerDrawable) seekbar.getProgressDrawable();
      fullBar.setColorFilter(rightColor, PorterDuff.Mode.SRC);
      fullBar.findDrawableByLayerId(R.id.progress).setColorFilter(leftColor, PorterDuff.Mode.SRC);
    }
  }

 // Set the seekbar position based on minValue, maxValue, and thumbPosition
 // seekbar position is an integer in the range [0,numberOfSteps] and is determined by MinValue,
 // MaxValue and ThumbPosition
 private void setSeekbarPosition() {
    float seekbarPosition = ((thumbPosition - minValue) / (maxValue - minValue)) * numberOfSteps;

    if (DEBUG) {
      Log.d(LOG_TAG, "Trying to recalculate seekbar position "
        + minValue + "/" + maxValue + "/" + thumbPosition + "/" + seekbarPosition);
    }

    // Set the thumb position on the seekbar
    // I've enabled animations when changing progress programmatically, 
    // as it does in the iOS version. 
    // However, animation is disabled when setting the NumberOfSteps property.
    if (VERSION.SDK_INT >= VERSION_CODES.N) {
      seekbar.setProgress((int) seekbarPosition, notice);
    } else {
      seekbar.setProgress((int) seekbarPosition);
    }
  }

  /**
   * Sets whether or not the slider thumb should be shown
   *
   * @param enabled Whether or not the slider thumb should be shown
   */
  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_BOOLEAN,
    defaultValue = "True")
  @SimpleProperty(description = "Sets whether or not to display the slider thumb.",
     userVisible = true)
  public void ThumbEnabled(boolean enabled) {
    thumbEnabled = enabled;
    int alpha = thumbEnabled ? 255 : 0;
    if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN) {
      new SeekBarHelper().getThumb(alpha);
    }

    // The Seekbar will respond to touch without the thumb. Consume the event with thumb disabled.
    seekbar.setOnTouchListener(new View.OnTouchListener() {
      @Override
      public boolean onTouch(View view, MotionEvent motionEvent) {
        return !thumbEnabled;
      }
    });
  }

  /**
   * Whether or not the slider thumb is being be shown.
   *
   * @return Whether or not the slider thumb is being be shown
   */
  @SimpleProperty(category = PropertyCategory.APPEARANCE,
      description = "Returns whether or not the slider thumb is being be shown",
      userVisible = true)
  public boolean ThumbEnabled() {
    return thumbEnabled;
  }

  /**
   * Sets the position of the slider thumb. If this value is greater than {@link #MaxValue()},
   * then it will be set to same value as {@link #MaxValue()}. If this value is less than
   * {@link #MinValue()}, then it will be set to same value as {@link #MinValue()}.
   *
   * @param position the position of the slider thumb. This value should be between
   *                 sliderMinValue and sliderMaxValue. If this value is not within the min and
   *                 max, then it will be calculated.
   */
  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_FLOAT,
      defaultValue = Component.SLIDER_THUMB_VALUE + "")
  @SimpleProperty(description = "Sets the position of the slider thumb. " +
      "If this value is greater than MaxValue, then it will be set to same value as MaxValue. " +
      "If this value is less than MinValue, then it will be set to same value as MinValue.",
      userVisible = true)
  public void ThumbPosition(float position) {
    // constrain thumbPosition between minValue and maxValue
    thumbPosition = Math.max(Math.min(position, maxValue), minValue);
    if (DEBUG) {
      Log.d(LOG_TAG, "ThumbPosition is set to: " + thumbPosition);}
    setSeekbarPosition();
  }

  /**
   * Returns the position of slider thumb
   *
   * @suppressdoc
   * @return the slider thumb position
   */
  @SimpleProperty(category = PropertyCategory.APPEARANCE,
      description = "Returns the position of slider thumb", userVisible = true)
  public float ThumbPosition() {
    return thumbPosition;
  }


  /**
   * Sets the minimum value of slider. If the new minimum is greater than the
   * current maximum, then minimum and maximum will both be set to this value.
   */
  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_FLOAT,
      defaultValue = Component.SLIDER_MIN_VALUE + "")
  @SimpleProperty(description = "Sets the minimum value of slider. " +
      "If the new minimum is greater than the current maximum, then minimum and maximum will " +
      "both be set to this value.",
      userVisible = true)
  public void MinValue(float value) {
    minValue = value;
    // increase maxValue if necessary to accommodate the new minimum
    maxValue = Math.max(value, maxValue);

    if (DEBUG) {
      Log.d(LOG_TAG, "Min value is set to: " + value);
    }
    thumbPosition = ((maxValue - minValue) * (float) seekbar.getProgress() / numberOfSteps) + minValue;
  }


  /**
   * Returns the value of slider min value.
   *
   * @suppressdoc
   * @return the value of slider min value.
   */
  @SimpleProperty(category = PropertyCategory.APPEARANCE,
      description = "Returns the value of slider min value.", userVisible = true)
  public float MinValue() {
    return minValue;
  }

  /**
   * Sets the maximum value of slider. If the new maximum is less than the
   * current minimum, then minimum and maximum will both be set to this value.
   */
  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_FLOAT,
      defaultValue = Component.SLIDER_MAX_VALUE + "")
  @SimpleProperty(description = "Sets the maximum value of slider. " +
      "If the new maximum is less than the current minimum, then minimum and maximum will both " +
      "be set to this value. ",
      userVisible = true)
  public void MaxValue(float value) {
    maxValue = value;
    minValue = Math.min(value, minValue);

    if (DEBUG) {
     Log.d (LOG_TAG, "Max value is set to: " + value);
    }
    thumbPosition = ((maxValue - minValue) * (float) seekbar.getProgress() / numberOfSteps) + minValue;
  }

  /**
   * Returns the slider max value
   *
   * @suppressdoc
   * @return the slider max value
   */
  @SimpleProperty(category = PropertyCategory.APPEARANCE,
      description = "Returns the slider max value.", userVisible = true)
  public float MaxValue() {
    return maxValue;
  }

  /**
   * Get the number of points from the slider scale. 
   */
  @SimpleProperty(category = PropertyCategory.APPEARANCE, 
      description = "Number of steps on the slider scale. Combined with" +
        "MinValue and MaxValue, it allows you to get the slider precision that you want, e.g. MinValue = 0," + 
        "MaxValue = 150, NumberOfSteps = 1000, the slider will change position every 0.15.",
      userVisible = true)
  public int NumberOfSteps() {
    return numberOfSteps;
  }

  /**
   * Set the number of points on the slider scale.
   * Combined with MinValue and MaxValue, it allows you to get the slider precision that you want,
   * e.g. MinValue = 0, MaxValue = 150, NumberOfSteps = 1000. The slider will change position every 0.15.
   */
  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_NON_NEGATIVE_INTEGER,
      defaultValue = "100")
  @SimpleProperty
  public void NumberOfSteps(int value) {
    numberOfSteps = value;
    // We save the position to restore it after setting the properties
    float oldPosition = thumbPosition;
    // We set the notice flag to false so that the user is not informed in any way about the change of this property
    notice = false;
    seekbar.setMax(value);
    // restore the original position
    thumbPosition = oldPosition;
    setSeekbarPosition();
    notice = true;
  }

  /**
   * Returns the color of the slider bar to the left of the thumb, as an alpha-red-green-blue
   * integer, i.e., {@code 0xAARRGGBB}.  An alpha of {@code 00}
   * indicates fully transparent and {@code FF} means opaque.
   *
   * @return left color in the format 0xAARRGGBB, which includes
   * alpha, red, green, and blue components
   */
  @SimpleProperty(
      description = "The color of slider to the left of the thumb.",
      category = PropertyCategory.APPEARANCE)
  @IsColor
  public int ColorLeft() {
    return leftColor;
  }

  /**
   * Specifies the color of the slider bar to the left of the thumb as an alpha-red-green-blue
   * integer, i.e., {@code 0xAARRGGBB}.  An alpha of {@code 00}
   * indicates fully transparent and {@code FF} means opaque.
   *
   * @param argb background color in the format 0xAARRGGBB, which
   * includes alpha, red, green, and blue components
   */
  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_COLOR,
      defaultValue = initialLeftColorString)
  @SimpleProperty
  public void ColorLeft(int argb) {
    leftColor = argb;
    setSliderColors();
  }

  /**
   * Returns the color of the slider bar to the right of the thumb, as an alpha-red-green-blue
   * integer, i.e., {@code 0xAARRGGBB}.  An alpha of {@code 00}
   * indicates fully transparent and {@code FF} means opaque.
   *
   * @return right color in the format 0xAARRGGBB, which includes
   * alpha, red, green, and blue components
   */
  @SimpleProperty(
      description = "The color of slider to the right of the thumb.",
      category = PropertyCategory.APPEARANCE)
  @IsColor
  public int ColorRight() {
    return rightColor;
  }

  /**
   * Specifies the color of the slider bar to the right of the thumb as an alpha-red-green-blue
   * integer, i.e., {@code 0xAARRGGBB}.  An alpha of {@code 00}
   * indicates fully transparent and {@code FF} means opaque.
   *
   * @param argb background color in the format 0xAARRGGBB, which
   * includes alpha, red, green, and blue components
   */
  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_COLOR,
      defaultValue = initialRightColorString)
  @SimpleProperty
  public void ColorRight(int argb) {
    rightColor = argb;
    setSliderColors();
  }

  /**
   * Returns the color of the thumb slider, as an alpha-red-green-blue
   * integer, i.e., {@code 0xAARRGGBB}.  An alpha of {@code 00}
   * indicates fully transparent and {@code FF} means opaque.
   *
   * @return thumb color in the format 0xAARRGGBB, which includes
   * alpha, red, green, and blue components
   */
  @SimpleProperty(
      description = "The slider thumb color",
      category = PropertyCategory.APPEARANCE)
  @IsColor
  public int ThumbColor() {
    return thumbColor;
  }

  /**
   * Specifies the color of the thumb slider as an alpha-red-green-blue
   * integer, i.e., {@code 0xAARRGGBB}.  An alpha of {@code 00}
   * indicates fully transparent and {@code FF} means opaque.
   *
   * @param argb thumb color in the format 0xAARRGGBB, which
   * includes alpha, red, green, and blue components
   */
  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_COLOR,
      defaultValue = initialThumbColorString)
  @SimpleProperty
  public void ThumbColor(int argb) {
    thumbColor = argb;
    setSliderColors();
  }

  @Override
  public View getView() {
    return seekbar;
  }

  @Override
  public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
    // progress has been changed. Set the sliderThumbPosition and then trigger the event
    // Now convert this progress value (which is between 0-numberOfSteps), back to a value between the
    // range that user has set within minValue, maxValue

    // We check the notice flag so as not to trigger the event when we change the NumberOfSteps property.
    if (notice) {
      thumbPosition = ((maxValue - minValue) * (float) progress / numberOfSteps) + minValue;

      if (DEBUG) {
      Log.d(LOG_TAG, "onProgressChanged progress value [0 - numberOfSteps]: " + progress
          + ", reporting to user as: " + thumbPosition);
      }

      // Trigger the event, reporting this new value    
      PositionChanged(thumbPosition);
    }
  }

  /**
   * Indicates that position of the slider thumb has changed.
   */
  @SimpleEvent(description = "Triggered when the thumb slider position has changed.")
  public void PositionChanged(float thumbPosition) {
    EventDispatcher.dispatchEvent(this, "PositionChanged", thumbPosition);
  }

  /**
   * Indicates that the user has started a touch gesture.
   */
  @SimpleEvent(description = "Triggered when the user has started a touch gesture.")
  public void TouchDown() {
    EventDispatcher.dispatchEvent(this, "TouchDown");
  }

  /**
  * Indicates that the user has finished a touch gesture.
  */
  @SimpleEvent(description = "Triggered when the user has finished a touch gesture.")
  public void TouchUp() {
    EventDispatcher.dispatchEvent(this, "TouchUp");
  }

  @Override
  public void onStartTrackingTouch(SeekBar seekBar) {
    TouchDown();
  }

  @Override
  public void onStopTrackingTouch(SeekBar seekBar) {
    TouchUp();
  }

  /**
   * Returns the component's vertical height, measured in pixels.
   *
   * @return height in pixels
   */
  @Override
  public int Height() {
    //NOTE(kashi01): overriding and removing the annotation, because we don't want to give user
    //ability to change the slider height and don't want display this in our block editor
    return getView().getHeight();
  }

  /**
   * Specifies the component's vertical height, measured in pixels.
   *
   * @param height in pixels
   */
  @Override
  public void Height(int height) {
    //NOTE(kashi01): overriding and removing the annotation, because we don't want to give user
    //ability to change the slider height and don't want display this in our block editor
    container.setChildHeight(this, height);
  }
}
