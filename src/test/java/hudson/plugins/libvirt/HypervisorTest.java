/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hudson.plugins.libvirt;

import org.jvnet.hudson.test.HudsonTestCase;

import com.sun.jna.Native;
import com.vmware.vim25.mo.VirtualMachine;

/**
 *
 * @author mmornati
 */
public class HypervisorTest extends HudsonTestCase {
    private static final int WAIT_TIME = 60000;
    static transient Hypervisor hp = null;
    private Native nativejna;
    @Override
    public void setUp() throws Exception {
        super.setUp();

    }

    public void testCreation() throws Exception {
         hp = new Hypervisor("ESX", "vmhost4.corena.no", 443 , "", "vmadmin", "Flax07%");
        for (JenkinsVirtualMachine virtualMachine : hp.getVirtualMachines()) {
            VirtualMachine dom = virtualMachine.getHypervisor().getDomain(virtualMachine.getName());
            if (dom.getName().equals("servicesci")) {
                System.out.println(virtualMachine + dom.getRuntime().getPowerState().toString()+":"+dom.getGuest().getGuestState());
                new VirtualMachineLauncher(null,  null, dom.getName()).ensureIsPowerOn(System.out, dom);
            }
            
        }
//        Thread th = new MyThread();
//        Thread th1 = new MyThread();
//        Thread th2 = new MyThread();
//        th.start();
//        th1.start();
//        th2.start();
//        th2.join();
//        th1.join();
//        th.join();
        Thread.sleep(1000);
        assertEquals("Wrong Virtual Machines Size", 13, hp.getVirtualMachines().size());
        hp.stop();
        Thread.sleep(1000);
    }
    
}

