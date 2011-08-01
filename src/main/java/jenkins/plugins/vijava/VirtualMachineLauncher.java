/**
 *  Copyright (C) 2010, Byte-Code srl <http://www.byte-code.com>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Date: Mar 04, 2010
 * Author: Marco Mornati<mmornati@byte-code.com>
 */
package jenkins.plugins.vijava;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.IOException;
import java.io.PrintStream;
import java.rmi.RemoteException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;

import com.vmware.vim25.FileFault;
import com.vmware.vim25.InsufficientResourcesFault;
import com.vmware.vim25.InvalidState;
import com.vmware.vim25.ManagedEntityStatus;
import com.vmware.vim25.RuntimeFault;
import com.vmware.vim25.TaskInProgress;
import com.vmware.vim25.TaskInfoState;
import com.vmware.vim25.ToolsUnavailable;
import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualMachineToolsStatus;
import com.vmware.vim25.VmConfigFault;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;

public class VirtualMachineLauncher extends ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(VirtualMachineLauncher.class.getName());
    private ComputerLauncher delegate;
    private transient JenkinsVirtualMachine virtualMachine;
    private String hypervisorDescription;
    private String virtualMachineName;
    private static final int WAIT_TIME = 60000;

    @DataBoundConstructor
    public VirtualMachineLauncher(ComputerLauncher delegate, String hypervisorDescription, String virtualMachineName) {
        super();
        this.delegate = delegate;
        this.virtualMachineName = virtualMachineName;
        this.hypervisorDescription = hypervisorDescription;
        buildVirtualMachine();
    }

    private void buildVirtualMachine() {
        if (hypervisorDescription != null && virtualMachineName != null) {
            LOGGER.log(Level.INFO, "Building virtual machine object from names");
            Hypervisor hypervisor = null;
            for (Cloud cloud : Hudson.getInstance().clouds) {
                if (cloud instanceof Hypervisor && ((Hypervisor) cloud).getHypervisorDescription().equals(hypervisorDescription)) {
                    hypervisor = (Hypervisor) cloud;
                    break;
                }
            }
            LOGGER.log(Level.INFO, "Hypervisor found... getting Virtual Machines associated");
            
            for (JenkinsVirtualMachine vm : hypervisor.getVirtualMachines()) {
                if (vm.getName().equals(virtualMachineName)) {
                    virtualMachine = vm;
                    break;
                }
            }
        }
    }

    public ComputerLauncher getDelegate() {
        return delegate;
    }

    public JenkinsVirtualMachine getVirtualMachine() {
        return virtualMachine;
    }

    @Override
    public boolean isLaunchSupported() {
        return delegate.isLaunchSupported();
    }

    @Override
    public void launch(SlaveComputer slaveComputer, TaskListener taskListener) throws IOException, InterruptedException {
        PrintStream logger =  taskListener.getLogger();
        launchVM(logger);
        delegate.launch(slaveComputer, taskListener);
    }

    public void launchVM(PrintStream logger) throws IOException {
        logger.println("Getting connection to the virtual datacenter");
        if (virtualMachine == null) {
            logger.println("No connection ready to the Hypervisor... reconnecting...");
            buildVirtualMachine();
        }
        try {
            VirtualMachine domain = virtualMachine.getHypervisor().getDomain(virtualMachine.getName());
            logger.println("Looking for the virtual machine on Hypervisor...");

            if (domain != null) {
                logger.println("Virtual Machine Found: "+domain.getGuest().getGuestState());
                ensureIsPowerOn(logger, domain);
                logger.println("Finished startup procedure... Connecting slave client");
            } else {
                logger.println("Error! Could not find virtual machine on the hypervisor");
                throw new IOException("VM not found!");
            }
        } catch (IOException e) {
            e.printStackTrace(logger);
            throw e;
        } catch (Exception t) {
            t.printStackTrace(logger);
            throw new IOException(t.getMessage(), t);
        }
    }

    public void ensureIsPowerOn(PrintStream logger, VirtualMachine domain) throws Exception {
        if (domain.getRuntime().getPowerState() == VirtualMachinePowerState.poweredOff) {
            logger.println("Starting virtual machine");
            Task task = domain.powerOnVM_Task(null);
            while(task.getTaskInfo().getState() == TaskInfoState.running) {
                Thread.sleep(1000);
                logger.println("Waiting for VM startup "+task.getTaskInfo().getProgress());
            }
            if (domain.getRuntime().getPowerState() != VirtualMachinePowerState.poweredOn) {
                throw new RuntimeException("Could not start VM "+domain.getName()); 
            }
            long maxWait = (60 * 1000) * 5;
            long starttime = System.currentTimeMillis();
            while (domain.getGuest().getToolsStatus() == VirtualMachineToolsStatus.toolsNotRunning) {
                Thread.sleep(1000);
                long used = System.currentTimeMillis() - starttime;
                logger.println("Waiting for OS startup waited:"+(used/1000));
                if (used > maxWait) {
                    throw new RuntimeException("Timeout waiting for vmware tools to start on "+domain.getName());
                }
            }
            logger.println("VM has started");
        } else {
            logger.println("Virtual machine is already running. No startup procedure required.");
        }
    }

    @Override
    public void afterDisconnect(SlaveComputer slaveComputer, TaskListener taskListener) {
        PrintStream logger = taskListener.getLogger();
        logger.println("Running disconnect procedure...");
        delegate.afterDisconnect(slaveComputer, taskListener);
        logger.println("Shutting down Virtual Machine...");
        try {
            powerOffVM(logger);
        } catch (Throwable t) {
            taskListener.fatalError(t.getMessage(), t);
        }
    }

    public void powerOffVM(PrintStream logger) throws Exception {
            logger.println("Looking for the virtual machine on Hypervisor...");
            VirtualMachine domain = virtualMachine.getHypervisor().getDomain(virtualMachine.getName());
            if (domain != null) {
                ensureIsPowerOff(logger, domain);
            } else {
                logger.println("Error! Could not find virtual machine on the hypervisor");
            }
        
    }

    public void ensureIsPowerOff(PrintStream logger, VirtualMachine domain) throws Exception {
        logger.println("Virtual Machine Found");
        if (domain.getRuntime().getPowerState() == VirtualMachinePowerState.poweredOn) {
            logger.println("Shutting down virtual machine: "+domain.getGuest().getAppHeartbeatStatus());
            if (domain.getGuest() != null && (domain.getGuest().getToolsStatus() == VirtualMachineToolsStatus.toolsOk || 
                    domain.getGuest().getToolsStatus() == VirtualMachineToolsStatus.toolsOld)) {
                domain.shutdownGuest();
                logger.println("Soft shutdown, vmware guest tools works.");
                
            } else {
                Task task = domain.powerOffVM_Task();
                logger.println("Hard shutdown, no vmware guest tools installed.");
                while(task.getTaskInfo().getState() == TaskInfoState.running) {
                    Thread.sleep(1000);
                    logger.println("Waiting for startup "+task.getTaskInfo().getProgress());
                }
                if (domain.getRuntime().getPowerState() != VirtualMachinePowerState.poweredOff) {
                    throw new RuntimeException("Could not shutdown VM "+domain.getName()); 
                }
            }
        } else {
            logger.println("Virtual machine is already suspended. No shutdown procedure required.");
        }
    }

    @Override
    public void beforeDisconnect(SlaveComputer slaveComputer, TaskListener taskListener) {
        delegate.beforeDisconnect(slaveComputer, taskListener);
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        return Hudson.getInstance().getDescriptor(getClass());
    }

    @Extension
    public static final Descriptor<ComputerLauncher> DESCRIPTOR = new Descriptor<ComputerLauncher>() {

        private String hypervisorDescription;
        private String virtualMachineName;
        private ComputerLauncher delegate;

        public String getDisplayName() {
            return "Virtual Machine Launcher";
        }

        public String getHypervisorDescription() {
            return hypervisorDescription;
        }

        public String getVirtualMachineName() {
            return virtualMachineName;
        }

        public ComputerLauncher getDelegate() {
            return delegate;
        }
    };
}
