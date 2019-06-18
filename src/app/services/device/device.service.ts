import { Injectable } from '@angular/core'
import { Platform } from '@ionic/angular'

declare var SecurityUtils: any
declare var rootdetection: any

@Injectable({
  providedIn: 'root'
})
export class DeviceService {
  constructor(private readonly platform: Platform) {}

  public checkForRoot(): Promise<boolean> {
    return new Promise((resolve, reject) => {
      if (this.platform.is('cordova') && this.platform.is('android')) {
        // TODO build own android root detection with https://github.com/scottyab/rootbeer
        rootdetection.isDeviceRooted((isRooted: 0 | 1 /* 1 = rooted, 0 = not rooted*/) => {
          resolve(isRooted === 1)
        }, reject)
      } else if (this.platform.is('ios') && this.platform.is('cordova')) {
        SecurityUtils.DeviceIntegrity.assess(function(result) {
          resolve(!result)
        })
      } else {
        console.warn('root detection skipped - no supported platform')
        resolve(false)
      }
    })
  }

  public onScreenCaptureStateChanged(callback: (captured: boolean) => void): void {
    if (this.platform.is('android') && this.platform.is('cordova')) {
      SecurityUtils.SecureScreen.onScreenCaptureStateChanged(function(captured: boolean) {
        callback(captured)
      })
    }
  }

  public removeScreenCaptureObservers(): void {
    if (this.platform.is('android') && this.platform.is('cordova')) {
      SecurityUtils.SecureScreen.removeScreenCaptureObservers()
    }
  }

  public onScreenshotTaken(callback: () => void) {
    if (this.platform.is('android') && this.platform.is('cordova')) {
      SecurityUtils.SecureScreen.onScreenshotTaken(function() {
        callback()
      })
    }
  }

  public removeScreenshotObservers(): void {
    if (this.platform.is('android') && this.platform.is('cordova')) {
      SecurityUtils.SecureScreen.removeScreenshotObservers()
    }
  }

  public async checkForElectron(): Promise<boolean> {
    return typeof navigator === 'object' && typeof navigator.userAgent === 'string' && navigator.userAgent.indexOf('Electron') >= 0
  }
}
