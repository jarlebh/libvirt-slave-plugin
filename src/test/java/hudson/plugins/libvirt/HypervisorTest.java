/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hudson.plugins.libvirt;

import java.util.Map;

import org.jvnet.hudson.test.HudsonTestCase;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.libvirt.DomainInfo.DomainState;

/**
 *
 * @author mmornati
 */
public class HypervisorTest extends HudsonTestCase {
    private static final int WAIT_TIME = 60000;
    static Hypervisor hp = null;
    @Override
    public void setUp() throws Exception {
        super.setUp();

    }

    public void testCreation() throws Exception {
         hp = new Hypervisor("ESX", "vmhost4.corena.no", 443 , "", "vmadmin", "Flax07%");
        for (VirtualMachine virtualMachine : hp.getVirtualMachines()) {
            System.out.println(virtualMachine + virtualMachine.getHypervisor().getDomain(virtualMachine.getName()).getInfo().toString());           
        }
        Thread th = new MyThread();
        Thread th1 = new MyThread();
        Thread th2 = new MyThread();
        th.start();
        th1.start();
        th2.start();
        th2.join();
        th1.join();
        th.join();
       // Thread.sleep(1000);
        assertEquals("Wrong Virtual Machines Size", 13, hp.getVirtualMachines().size());
        hp.stop();
       // Thread.sleep(1000);
    }
    private static class MyThread extends Thread {
        public void run() {
            
        for (VirtualMachine virtualMachine : hp.getVirtualMachines()) {
             try {
                 System.out.println(virtualMachine + virtualMachine.getHypervisor().getDomain(virtualMachine.getName()).getInfo().toString());
             } catch (Exception e) {
                 e.printStackTrace();
             }
        }
        }
    }
}

