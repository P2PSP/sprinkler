package com.luxunda.p2pspstreamer;

import android.content.Context;
import android.hardware.Camera;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

/**
 * Created by Arasthel on 27/6/15.
 */
public class CameraSizesArrayAdapter extends ArrayAdapter<Camera.Size> {

    public CameraSizesArrayAdapter(Context context, int resource, List<Camera.Size> objects) {
        super(context, resource, objects);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = super.getView(position, convertView, parent);
        TextView content = (TextView) v.findViewById(android.R.id.text1);
        Camera.Size size = getItem(position);
        content.setText(size.width+"x"+size.height);

        return v;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        return getView(position, convertView, parent);
    }
}
