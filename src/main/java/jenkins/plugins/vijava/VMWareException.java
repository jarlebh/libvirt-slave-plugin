/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package jenkins.plugins.vijava;

/**
 *
 * @author jbh
 */
class VMWareException extends Exception {

    public VMWareException(String string, Exception e) {
        super(string ,e);
    }
    
}
