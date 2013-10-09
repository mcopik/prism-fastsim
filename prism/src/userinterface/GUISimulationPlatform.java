/**
 * 
 */
package userinterface;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.TitledBorder;

/**
 * @author mcopik
 *
 */
public class GUISimulationPlatform extends JPanel
{
	private final static String TITLE = "Simulation platform";

	private JLabel labelPlatform = new JLabel();
	private JComboBox<String> selectPlatform = new JComboBox<>();
	private final static String LABEL_PLATFORM_CAPTION = "Select platform: ";
	private final static String AVAILABLE_PLATFORMS [] = {
		"CPU(default implementation)",
		"OpenCL"
	};
	
	private JLabel labelDevice = new JLabel();
	private JComboBox<String> selectDevice = new JComboBox<>();
	private final static String LABEL_DEVICE_CAPTION = "Select platform: ";
	private DefaultComboBoxModel<String> availableDevices = 
			new DefaultComboBoxModel<>( new String[]{"CPU"});
			
	public GUISimulationPlatform()
	{
		super(new GridBagLayout());
		this.setBorder(new TitledBorder(TITLE));
		initComponents();
	}
	public void setSimulationPlatform(SimulationInformation info)
	{
		switch(selectPlatform.getSelectedIndex())
		{
			case 0:
				info.setPlatform(SimulationInformation.Platform.CPU);
			break;
			case 1:
				info.setPlatform(SimulationInformation.Platform.OPENCL);
			break;
		}
	}
	private void initComponents()
	{
		GridBagConstraints gridBagConstraints;
		//SELECT PLATFORM
		gridBagConstraints = new GridBagConstraints();
		gridBagConstraints.gridx = 1;
		gridBagConstraints.gridy = 2;
		gridBagConstraints.anchor = GridBagConstraints.WEST;
		labelPlatform.setText(LABEL_PLATFORM_CAPTION);
		this.add(labelPlatform,gridBagConstraints);
		gridBagConstraints = new GridBagConstraints();
		gridBagConstraints.gridx = 3;
		gridBagConstraints.gridy = 2;
		gridBagConstraints.fill = GridBagConstraints.HORIZONTAL;
		selectPlatform.setModel(new DefaultComboBoxModel<String>(AVAILABLE_PLATFORMS));
		selectPlatform.addActionListener(new ActionListener()
		{
			public void actionPerformed(ActionEvent evt)
			{
				//
			}
		});
		this.add(selectPlatform,gridBagConstraints);
		
		//SELECT DEVICE
		gridBagConstraints = new GridBagConstraints();
		gridBagConstraints.gridx = 1;
		gridBagConstraints.gridy = 3;
		gridBagConstraints.anchor = GridBagConstraints.WEST;
		labelDevice.setText(LABEL_DEVICE_CAPTION);
		this.add(labelDevice,gridBagConstraints);
		gridBagConstraints = new GridBagConstraints();
		gridBagConstraints.gridx = 3;
		gridBagConstraints.gridy = 3;
		gridBagConstraints.fill = GridBagConstraints.HORIZONTAL;
		selectDevice.setEnabled(false);
		selectDevice.setModel(availableDevices);
		this.add(selectDevice,gridBagConstraints);
	}
}